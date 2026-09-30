package zcd.jellyfish.core.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.config.ProviderCacheSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 缓存保活：空闲时重发一个极小的、「复用同一前缀」的请求，把厂商侧的缓存 TTL 续上。
 * <p>
 * <b>它解决的问题</b>：部分厂商的 prompt 缓存只活几分钟（OpenAI 的内存缓存约 5–10 分钟），
 * 而人写代码的节奏很容易超过它——想两分钟、改几分钟再回来问一句，上一次建立的前缀就过期了，
 * 于是那一整段按未命中价重发一次。
 * <p>
 * <b>它是要花钱的，因此缺省关闭</b>：一次保活命中约 {@code 0.1×} 前缀，而它要防的是一次未命中
 * （多花约 {@code 0.9×}）。所以它划算的前提很明确：**这一轮空闲之后用户真的会回来接着问**。
 * 于是策略是自限的——{@link ProviderCacheSettings#getKeepAliveSeconds()} 是间隔，
 * 而每个空闲期最多续 {@link ProviderCacheSettings#MAX_KEEP_ALIVE_ROUNDS} 次
 * （那笔总账约 {@code 0.3×} 前缀，换掉一次未命中就是净赚），续完就停手。
 * <p>
 * <b>只保活「当前会话」</b>：{@code SessionManager.current()} 那一个。保活 N 个会话等于为 N 份
 * 前缀持续付费，而用户下一步只会接着用其中一个。
 * <p>
 * <b>怎么判断「空闲」</b>：用<b>消息条数</b>而不是 {@code updatedAt}——保活自己的用量记账也会刷新
 * {@code updatedAt}，用它会导致「保活把空闲期打断」，永远续不到第二次。消息条数只被真实回合改变，
 * 因此它一变就是「用户动过了」，空闲期重新开始计数。
 * <p>
 * <b>失败一律吞掉</b>：保活是锦上添花，绝不能让它把用户的一轮对话弄坏。任何异常只记日志，
 * 而且它跑在守护线程上，不阻塞进程退出。
 * <p>
 * 线程安全：扫周期在调度线程上跑，状态读写全部同步。
 *
 * @author zcd
 */
@Singleton
public class CacheKeepAlive implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CacheKeepAlive.class);

    /**
     * 扫描周期（毫秒）。
     * <p>
     * 与保活间隔无关：扫得勤一点才能让配置热更新尽快生效，而「到点了没有」由 {@code tick} 自己算。
     * 30 秒相对厂商那几分钟的 TTL 足够细。
     */
    private static final long TICK_MILLIS = 30_000L;

    /**
     * 保活请求里那句指令。
     * <p>
     * 措辞的意义只在于「不要模型发挥」：{@link PromptAssembler#buildKeepAlive} 已经把输出上限钉成
     * 一个 token，这一句是第二道保险。
     */
    private static final String INSTRUCTION = "[缓存保活] 无需回复内容。";

    /** 最多同时跟踪多少个会话的空闲期。 */
    private static final int MAX_SESSIONS = 64;

    /** 会话域服务：取当前会话、记用量。 */
    private final SessionManager sessionManager;

    /** 会话模型解析器：与对话共用同一套三级回落。 */
    private final SessionModelResolver sessionModelResolver;

    /** 模型门面：给出客户端。 */
    private final ModelManager modelManager;

    /** 提示词组装器：构造「只复用前缀」的请求。 */
    private final PromptAssembler promptAssembler;

    /** 运行时配置门面。 */
    private final RuntimeConfig runtimeConfig;

    /** 会话标识 → 当前空闲期的状态，按访问顺序淘汰。 */
    private final Map<String, IdlePeriod> idlePeriods = new LruPeriods();

    /** 扫描调度器。 */
    private final ScheduledExecutorService scheduler;

    /**
     * 构造保活器并启动扫描。
     * <p>
     * <b>为什么在构造期就起调度</b>：保活不需要任何调用点来触发——它要看的只有「当前会话空闲了多久」，
     * 而那件事随时可能发生。关掉它仍然是零成本的（{@code tick} 在读到间隔为 0 时直接返回），
     * 代价只是每 {@value #TICK_MILLIS} 毫秒一次空转。
     *
     * @param sessionManager       会话域服务
     * @param sessionModelResolver 会话模型解析器
     * @param modelManager         模型门面
     * @param promptAssembler      提示词组装器
     * @param runtimeConfig        运行时配置门面
     */
    @Inject
    public CacheKeepAlive(SessionManager sessionManager, SessionModelResolver sessionModelResolver,
                          ModelManager modelManager, PromptAssembler promptAssembler,
                          RuntimeConfig runtimeConfig) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.sessionModelResolver = Objects.requireNonNull(sessionModelResolver,
                "sessionModelResolver must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreads());
        this.scheduler.scheduleWithFixedDelay(new Scan(this), TICK_MILLIS, TICK_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 执行一次扫描：到点且还有额度时，发一个保活请求。
     * <p>
     * 包级可见是为了让测试直接驱动它，而不是去等真的调度器——时间相关的东西一旦只能靠等，
     * 用例就会变得又慢又飘。
     *
     * @param nowMillis 当前时刻（毫秒）
     */
    void tick(long nowMillis) {
        Session session = sessionManager.current();
        if (session == null) {
            return;
        }
        ProviderCacheSettings cache = cacheSettingsOf(session);
        if (cache == null || cache.getKeepAliveSeconds() <= 0) {
            return;
        }
        long intervalMillis = cache.getKeepAliveSeconds() * 1000L;
        IdlePeriod period = periodOf(session.getSessionId(), session.getMessages().size(), nowMillis);
        if (period.rounds >= ProviderCacheSettings.MAX_KEEP_ALIVE_ROUNDS
                || nowMillis - period.lastSentAt < intervalMillis) {
            return;
        }
        send(session, period, nowMillis);
    }

    /**
     * 取当前会话所用 provider 的缓存设置。
     * <p>
     * <b>解析不到模型时静默放弃</b>：保活不是对话，它没有资格因为「没配模型」去刷日志或报警。
     *
     * @param session 会话运行态
     * @return 缓存设置；解析不到时返回 {@code null}
     */
    private ProviderCacheSettings cacheSettingsOf(Session session) {
        try {
            ResolvedModel resolvedModel = sessionModelResolver.resolve(session);
            return resolvedModel.getProvider().getCache();
        } catch (RuntimeException e) {
            LOG.debug("缓存保活跳过：模型解析失败 sessionId={} reason={}", session.getSessionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 发送一次保活，并把额度与时刻记下。
     * <p>
     * <b>额度先记、成功与否都算</b>：请求已经发出去了，钱就花掉了（与压缩那边「用量先记」同理）。
     * 计入额度也是必须的——不然一个持续失败的对端会让我们每 30 秒试一次，永远试下去。
     *
     * @param session    会话运行态
     * @param period     当前空闲期
     * @param nowMillis  当前时刻
     */
    private void send(Session session, IdlePeriod period, long nowMillis) {
        period.rounds++;
        period.lastSentAt = nowMillis;
        String sessionId = session.getSessionId();
        try {
            ResolvedModel resolvedModel = sessionModelResolver.resolve(session);
            LlmRequest request = promptAssembler.buildKeepAlive(session, resolvedModel, INSTRUCTION);
            if (request == null) {
                // 复现不出父请求的前缀（机械裁剪吞了开头）。发一个前缀不同的请求是按 1× 计费，
                // 比不保活更贵，因此宁可什么都不做
                LOG.debug("缓存保活跳过：前缀无法原样复现 sessionId={}", sessionId);
                return;
            }
            LlmClient client = modelManager.getClient(resolvedModel);
            LlmResponse response = client.chat(request);
            if (response != null && response.getUsage() != null) {
                // 保活花掉的 token 必须进账（否则 /usage 与实际账单对不上），但它不产生消息、
                // 也不该被当成「用户活动」——空闲期是靠消息条数判定的，因此两者不冲突
                sessionManager.recordUsage(sessionId, response.getUsage());
            }
            LOG.debug("缓存保活已发送: sessionId={} round={}", sessionId, period.rounds);
        } catch (RuntimeException e) {
            LOG.debug("缓存保活失败（不影响对话）: sessionId={} reason={}", sessionId, e.getMessage());
        }
    }

    /**
     * 取当前空闲期的状态，会话动过则重置。
     * <p>
     * <b>用消息条数判断「动过」</b>：保活与压缩都不产生消息，只有真实回合会，因此这个信号干净。
     * 重置时把 {@code lastSentAt} 设成「发现变化的那一刻」，于是「空闲到该续了」这件事
     * 只需要一个判断：{@code 现在 - lastSentAt >= 间隔}。
     *
     * @param sessionId    会话标识
     * @param messageCount 当前消息条数
     * @param nowMillis    当前时刻
     * @return 空闲期状态，保证非 {@code null}
     */
    private synchronized IdlePeriod periodOf(String sessionId, int messageCount, long nowMillis) {
        IdlePeriod period = idlePeriods.get(sessionId);
        if (period == null || period.messageCount != messageCount) {
            period = new IdlePeriod(messageCount, nowMillis);
            idlePeriods.put(sessionId, period);
        }
        return period;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /**
     * 一个会话的当前空闲期：从「最后一次真实活动」开始计数。
     */
    private static final class IdlePeriod {

        /** 建立本空闲期时的消息条数：它一变就说明用户动过。 */
        private final int messageCount;

        /** 上一次发保活的时刻；建立时等于「发现变化的那一刻」。 */
        private long lastSentAt;

        /** 本空闲期已续的次数。 */
        private int rounds;

        /**
         * 构造空闲期。
         *
         * @param messageCount 建立时的消息条数
         * @param nowMillis    建立时刻
         */
        IdlePeriod(int messageCount, long nowMillis) {
            this.messageCount = messageCount;
            this.lastSentAt = nowMillis;
        }
    }

    /**
     * 把一次扫描包成任务，并兜住所有异常。
     * <p>
     * <b>为什么不用方法引用直接投给调度器</b>：{@code scheduleWithFixedDelay} 的任务一旦抛出异常，
     * 后续执行会被<b>静默取消</b>——保活会从此彻底停摆，而日志里什么都没有。
     * 包一层是唯一能把「一次意外」与「永久失效」分开的地方。
     */
    private static final class Scan implements Runnable {

        /** 宿主。 */
        private final CacheKeepAlive keepAlive;

        /**
         * 构造扫描任务。
         *
         * @param keepAlive 宿主
         */
        Scan(CacheKeepAlive keepAlive) {
            this.keepAlive = keepAlive;
        }

        @Override
        public void run() {
            try {
                keepAlive.tick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOG.warn("缓存保活扫描出错（已忽略，下个周期继续）", e);
            }
        }
    }

    /**
     * 守护线程工厂。
     * <p>
     * 守护是关键：保活线程不该让进程退不出去。
     */
    private static final class DaemonThreads implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cache-keepalive");
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 按访问顺序淘汰的会话空闲期表。
     */
    private static final class LruPeriods extends LinkedHashMap<String, IdlePeriod> {

        /** 序列化标识（本类从不序列化，仅为满足 Serializable 约定）。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造按访问顺序排序的映射。
         */
        LruPeriods() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IdlePeriod> eldest) {
            return size() > MAX_SESSIONS;
        }
    }
}
