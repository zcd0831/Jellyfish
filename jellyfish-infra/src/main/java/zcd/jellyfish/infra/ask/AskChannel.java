package zcd.jellyfish.infra.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.infra.config.RuntimeConfig;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 向用户提问的通道：同步阻塞的提问方与外壳界面之间唯一的交接点。
 * <p>
 * <b>它解决什么问题</b>：提问发生在 {@code react} 线程上并且是同步的（工具处理器的返回值就是
 * 这次工具调用的结果，拿到答案才有意义），而能回答的人只在<b>渲染线程</b>里存在。两侧因此需要一次
 * 跨线程交接：写侧阻塞等待，读侧每帧取件。这与人工审批（{@code ApprovalChannel}）形态相同，
 * 只是语义不同——审批的结论是「放不放行」，提问的结论是「答案是什么」。
 * <p>
 * <b>为什么是通道而不是扩展点</b>：提问需要独占终端的模态交互，而插件在架构上碰不到界面。
 * 但提问的<b>发起方</b>恰恰常常是插件（{@code ask_user} 工具就在插件里）。解法与子代理委派相同：
 * 给插件一条<b>出向边</b>——它实现 api 侧的 {@link AskPort}，经
 * {@code PluginContext.askUser()} 交给插件；答复者仍然只能是外壳（{@link #attach()}）。
 * <p>
 * <b>与审批的关键口径差异：它不是 fail-closed</b>。没有答复者时返回的是
 * {@link AskAnswer.Status#UNAVAILABLE}，而不是「拒绝」——提问不涉及权限，拿不到答案不等于
 * 哪次调用被禁止。这条差异是刻意的：审批者缺席时放行等于静默放宽权限，而提问缺席时
 * 让模型照自己的判断继续，代价只是它可能猜错并说出来。
 * <p>
 * <b>为什么一次只交接一个（每个会话）</b>：提问在界面上是一个模态选择框，同屏只能显示一个。
 * 因此<b>每个会话</b>同时只有一个「当前待答问题」（读侧每帧读它），该会话其余提问按到达顺序排队；
 * 排队数封顶 {@link #MAX_WAITING}，超出即判定为「问不到人」——{@code react} 池并发上限有限，
 * 真排到上限说明答复者已经不在了，让请求无限堆积只会把内存与线程一起拖住。
 * <p>
 * <b>槽位按会话隔离</b>：头槽位是<b>每会话一个</b>，会话之间不互相排队。否则会话 A 的提问没答完，
 * 会话 B 的提问就得排在后面，而答复者只能看到一条，于是 B 的回合一直阻塞到超时。
 * <p>
 * <b>「只接受第一次结论」怎么保证</b>：裁决、超时、通道关闭三方都可能给出结论，它们全部经
 * {@link #finish} 在<b>同一把锁</b>内完成「查等待者 → 摘掉 → 推进当前槽位」，
 * 因此只有一个调用方能在映射里找到那个等待者。先到者胜出，后到者当作无事发生——
 * 若不做这一步，「超时已收敛、界面随后又按下确认」会把一个已经结束的等待改成有答案。
 * <p>
 * 线程安全：读侧（{@link #pending(String)}）走 {@code ConcurrentHashMap} 无锁读；
 * 写侧状态变更全部在一把锁内完成。
 *
 * @author zcd
 */
@Singleton
public class AskChannel implements AskPort {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AskChannel.class);

    /** 每个会话排队等待答复的最大提问数（不含当前展示的那一条）。 */
    public static final int MAX_WAITING = 8;

    /** 无答复者时的说明。 */
    public static final String NO_ASKER = "当前外壳没有交互界面，无法把提问送达用户";

    /** 提问通道已关闭时的说明。 */
    public static final String DETACHED = "提问通道已关闭，无法把提问送达用户";

    /** 排队超出上限时的说明。 */
    public static final String QUEUE_FULL = "待答提问过多，无法把提问送达用户";

    /** 线程被中断时的说明。 */
    public static final String INTERRUPTED = "提问等待被中断（回合已取消）";

    /** 用户放弃作答时的说明。 */
    public static final String CANCELLED = "用户取消了这次提问，没有回答";

    /** 运行时配置门面：提问超时每次现读，改配置后无需重启即可生效。 */
    private final RuntimeConfig runtimeConfig;

    /** 是否已挂上答复者（外壳启动时置位）。 */
    private volatile boolean attached;

    /**
     * 每会话的当前头槽位：无待答提问的会话不出现在表里。
     * <p>
     * 用 {@link ConcurrentHashMap} 而不是在 {@link #lock} 里读：读侧（外壳每帧取件）不应该等写侧，
     * 而写侧推进头槽位本来就是原子的替换。
     */
    private final ConcurrentHashMap<String, Pending> heads = new ConcurrentHashMap<String, Pending>();

    /** 每会话的排队区（不含头槽位），与 {@link #waiters} 同增同减。 */
    private final Map<String, Deque<Pending>> waiting = new LinkedHashMap<String, Deque<Pending>>();

    /** 全部在途提问（含头槽位与排队中的），键为请求 id：用于按 id 定位它属于哪个会话。 */
    private final Map<String, Pending> pendingById = new LinkedHashMap<String, Pending>();

    /** 全部在途提问的等待者，键为请求 id：供裁决与关闭时定位。 */
    private final Map<String, Waiter> waiters = new LinkedHashMap<String, Waiter>();

    /** 状态变更锁：入队、裁决、摘除三处共用。 */
    private final Object lock = new Object();

    /**
     * 构造提问通道。初始未挂载，因此默认行为是「问不到人」。
     *
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public AskChannel(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
    }

    /**
     * 挂上答复者：此后的 {@link #ask} 会真的等待答复。外壳在启动时调用。
     */
    public void attach() {
        attached = true;
    }

    /**
     * 摘下答复者并排空未决提问。
     * <p>
     * <b>排空是必须的</b>：外壳退出时若不把等待中的提问一并收敛，那些 {@code react} 线程会一直阻塞
     * 到超时；排空让它们立刻拿到「问不到人」并继续往下走。
     */
    public void detach() {
        attached = false;
        List<String> abandoned;
        synchronized (lock) {
            abandoned = new ArrayList<String>(waiters.keySet());
        }
        for (String id : abandoned) {
            finish(id, AskAnswer.unavailable(DETACHED));
        }
        if (!abandoned.isEmpty()) {
            LOG.info("提问通道关闭，已收敛 {} 条未决提问", abandoned.size());
        }
    }

    @Override
    public AskAnswer ask(AskRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        // 超时现读而不是构造时缓存：与审批超时同口径，改配置后无需重启即可生效
        return request(request, Duration.ofSeconds(runtimeConfig.getAskSettings().getTimeoutSeconds()));
    }

    /**
     * 向用户提问并阻塞等待答复（{@code react} 线程调用）。
     *
     * @param request 提问请求，不可为 {@code null}
     * @param timeout 等待超时，不可为 {@code null} 且必须为正
     * @return 答复：拿到答案为 ANSWERED，其余一切情况（无答复者 / 超时 / 排队满 / 通道关闭 / 中断）
     *         均为对应终态，绝不抛异常
     */
    public AskAnswer request(AskRequest request, Duration timeout) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (!attached) {
            return AskAnswer.unavailable(NO_ASKER);
        }
        Pending pending = new Pending(request);
        Waiter waiter = new Waiter();
        if (!enqueue(pending, waiter)) {
            // 入队失败的原因在锁内定下，但这里重读一次 attached 就足够区分：关闭是一次性事件
            return AskAnswer.unavailable(attached ? QUEUE_FULL : DETACHED);
        }
        AskAnswer unanswered = null;
        try {
            if (!waiter.latch.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                unanswered = AskAnswer.timedOut(timeoutReason(timeout));
            }
        } catch (InterruptedException e) {
            // 中断在此处只有一种来源：回合被取消 / 进程在退出。恢复中断位让上层也能看到
            Thread.currentThread().interrupt();
            unanswered = AskAnswer.cancelled(INTERRUPTED);
        }
        if (unanswered != null) {
            // 落定失败说明裁决方抢先给出了结论，此时按它的结论返回
            finish(pending.getId(), unanswered);
        }
        return waiter.answer;
    }

    /**
     * 取一个待答提问（跨会话最早的那一条）。
     * <p>
     * <b>它只服务「不知道自己是哪个会话」的晚到客户端</b>（{@code GET /asks}）。
     * 外壳绘制提问浮层应当用 {@link #pending(String)}：那一个才是「本会话的头槽位」。
     *
     * @return 最早的待答提问；没有时为 {@link Optional#empty()}
     */
    public Optional<Pending> pending() {
        Pending oldest = null;
        for (Pending candidate : heads.values()) {
            if (oldest == null || candidate.getTimestamp() < oldest.getTimestamp()) {
                oldest = candidate;
            }
        }
        return Optional.ofNullable(oldest);
    }

    /**
     * 取指定会话当前待答提问，供外壳每帧绘制提问浮层。
     *
     * @param sessionId 会话标识，可为 {@code null}（归入无会话槽位）
     * @return 该会话的当前提问；没有时为 {@link Optional#empty()}
     */
    public Optional<Pending> pending(String sessionId) {
        return Optional.ofNullable(heads.get(sessionKey(sessionId)));
    }

    /**
     * 取指定会话当前全部待答提问：头槽位在前，其后是排队区（按入队先后）。
     * <p>
     * 与审批同口径：列表里<b>只有第一条可以被裁决</b>——{@link #resolve} 只对头槽位生效。
     * 它适合渲染「第 1 / 共 N 条」，不适合拿去做批量裁决；返回的是一份瞬时快照。
     *
     * @param sessionId 会话标识，可为 {@code null}（归入无会话槽位）
     * @return 不可修改的列表；没有待答提问时为空列表而非 {@code null}
     */
    public List<Pending> pendingAsks(String sessionId) {
        String key = sessionKey(sessionId);
        List<Pending> result = new ArrayList<Pending>();
        // 头槽位与排队区必须在同一把锁里读：两者是同一次裁决的两个半边，
        // 分开读会取到「已经推进过头、队列还没轮到的」那种中间态（列表里出现重复或缺口）
        synchronized (lock) {
            Pending head = heads.get(key);
            if (head != null) {
                result.add(head);
            }
            Deque<Pending> queue = waiting.get(key);
            if (queue != null) {
                result.addAll(queue);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 取指定会话排队中的提问数（不含头槽位）。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 排队中的提问数
     */
    int waitingCount(String sessionId) {
        synchronized (lock) {
            Deque<Pending> queue = waiting.get(sessionKey(sessionId));
            return queue == null ? 0 : queue.size();
        }
    }

    /**
     * 给出答复（渲染线程调用）。
     * <p>
     * 只对<b>某个会话当前展示的那一条</b>生效：排队中的提问答复者根本看不到，也就无从作答，
     * 对它调用等于无事发生。同一条提问的第一次结论胜出，之后的调用（超时后用户才按下、
     * 重复按键）静默丢弃。
     *
     * @param id     请求 id，可为 {@code null}
     * @param answer 答复，不可为 {@code null}
     * @return 本次调用真的落定了一条头槽位返回 {@code true}；无事发生时返回 {@code false}
     */
    public boolean resolve(String id, AskAnswer answer) {
        if (id == null || answer == null) {
            return false;
        }
        Pending target;
        synchronized (lock) {
            target = pendingById.get(id);
        }
        if (target == null || !Objects.equals(target, heads.get(sessionKey(target.getSessionId())))) {
            return false;
        }
        return finish(id, answer);
    }

    /**
     * 落定一条在途提问：摘掉等待者、推进当前槽位、放行等待线程。
     * <p>
     * 「查找 + 摘除」在同一把锁内完成，因此同一条提问的多个结论来源只有一个能成功；
     * 返回 {@code false} 表示这条提问已被别人裁决（或早已超时）。
     *
     * @param id     请求 id
     * @param answer 答复
     * @return 本次调用真正落定返回 {@code true}
     */
    private boolean finish(String id, AskAnswer answer) {
        Waiter waiter;
        synchronized (lock) {
            waiter = waiters.remove(id);
            if (waiter == null) {
                return false;
            }
            // answer 在锁内赋值：输给竞态的那一方会在自己 finish 失败前获取过同一把锁，
            // 因此它随后读到的一定是已发布的值（锁提供 happens-before），不会是 null。
            // 原先写在锁外，超时与裁决同时发生时会读到 null，而调用方按「保证非 null」处理它
            waiter.answer = answer;
            Pending pending = pendingById.remove(id);
            if (pending != null) {
                advance(pending);
            }
        }
        waiter.latch.countDown();
        return true;
    }

    /**
     * 从某会话的槽位表中摘掉一条已落定的提问，并推进该会话的头槽位。
     * <p>
     * 必须是头槽位才能推进：排队中的提问被摘掉只影响队列自己，
     * 把队列里的下一个提为头就是这个提问的副作用。
     *
     * @param pending 已落定的提问
     */
    private void advance(Pending pending) {
        String key = sessionKey(pending.getSessionId());
        Pending head = heads.get(key);
        if (head == null) {
            return;
        }
        if (head.getId().equals(pending.getId())) {
            Deque<Pending> queue = waiting.get(key);
            if (queue == null || queue.isEmpty()) {
                heads.remove(key);
                waiting.remove(key);
            } else {
                heads.put(key, queue.pollFirst());
            }
        } else {
            Deque<Pending> queue = waiting.get(key);
            if (queue != null) {
                queue.remove(pending);
            }
        }
    }

    /**
     * 入队一条提问：该会话的头槽位空闲则直接占用，否则排队（超出上限返回失败）。
     *
     * @param pending 提问
     * @param waiter  等待者
     * @return 入队成功返回 {@code true}
     */
    private boolean enqueue(Pending pending, Waiter waiter) {
        synchronized (lock) {
            if (!attached) {
                // 与 ask 开头的检查之间可能刚好发生了一次 detach，这里重查一次：
                // 不重查的话这条提问会进了队列却没人看（答复者已经走了），白等到超时
                return false;
            }
            String key = sessionKey(pending.getSessionId());
            if (heads.get(key) == null) {
                heads.put(key, pending);
                pendingById.put(pending.getId(), pending);
                waiters.put(pending.getId(), waiter);
                return true;
            }
            Deque<Pending> queue = waiting.get(key);
            if (queue == null) {
                queue = new ArrayDeque<Pending>();
            }
            if (queue.size() >= MAX_WAITING) {
                // 只拒绝这一条新提问：已排队的那些仍在队列里、仍会被逐个提为头槽位。
                // 原先这里连整个队列一起摘掉，于是它们既排不到头、又留在 pendingById 里，
                // 用户点了作答也无效（resolve 只认头槽位），只能等到超时
                return false;
            }
            queue.addLast(pending);
            waiting.put(key, queue);
            pendingById.put(pending.getId(), pending);
            waiters.put(pending.getId(), waiter);
            return true;
        }
    }

    /**
     * 取会话在槽位表里的键。
     * <p>
     * {@link AskRequest#getSessionId()} 允许为 {@code null}，而映射不接受 {@code null} 键，
     * 因此无会话的提问归入一个固定的空串槽位；它不会与任何真实会话相撞（会话标识不可为空白）。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 槽位键，保证非 {@code null}
     */
    private static String sessionKey(String sessionId) {
        return sessionId == null ? "" : sessionId;
    }

    /**
     * 生成超时说明。
     *
     * @param timeout 等待超时
     * @return 说明文本
     */
    private static String timeoutReason(Duration timeout) {
        long millis = Math.max(1L, timeout.toMillis());
        String amount = millis < TimeUnit.SECONDS.toMillis(1) ? millis + " 毫秒" : timeout.getSeconds() + " 秒";
        return "用户在 " + amount + "内没有回答";
    }

    /**
     * 待答提问：一次「向用户提问」的全部展示信息。
     * <p>
     * 不可变，可安全跨线程传递（由 {@code react} 线程构造、渲染线程读取）。
     *
     * @author zcd
     */
    public static final class Pending {

        /** 请求标识，供渲染线程作答时回填。 */
        private final String id;

        /** 提问内容。 */
        private final AskRequest request;

        /** 提问发生时刻（毫秒）。 */
        private final long timestamp;

        /**
         * 构造待答提问。
         *
         * @param request 提问内容，不可为 {@code null}
         */
        public Pending(AskRequest request) {
            this.id = UUID.randomUUID().toString();
            this.request = Objects.requireNonNull(request, "request must not be null");
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * 获取请求标识。
         *
         * @return 请求标识，保证非 {@code null}
         */
        public String getId() {
            return id;
        }

        /**
         * 获取提问内容。
         *
         * @return 提问内容，保证非 {@code null}
         */
        public AskRequest getRequest() {
            return request;
        }

        /**
         * 获取会话标识。
         *
         * @return 会话标识，可能为 {@code null}
         */
        public String getSessionId() {
            return request.getSessionId();
        }

        /**
         * 获取提问发生时刻。
         *
         * @return 毫秒时间戳
         */
        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * 等待者：一次阻塞等待的闩锁与结论。
     * <p>
     * 只会被 {@link #finish} 写入一次，且写入发生在闩锁放行之前，因此等待线程读到的必然是自己那次的结论。
     */
    private static final class Waiter {

        /** 结论闩锁。 */
        private final CountDownLatch latch = new CountDownLatch(1);

        /** 答复，由 {@link #finish} 在放行前写入。 */
        private volatile AskAnswer answer;
    }
}
