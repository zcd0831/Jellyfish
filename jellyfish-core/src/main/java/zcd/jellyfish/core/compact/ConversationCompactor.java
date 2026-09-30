package zcd.jellyfish.core.compact;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CompactionDirective;
import zcd.jellyfish.api.extension.CompactionPreRequest;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.core.prompt.ContextUsage;
import zcd.jellyfish.core.prompt.TokenEstimator;
import zcd.jellyfish.core.prompt.ToolPairing;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionCompaction;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话压缩器：多花一次模型调用把旧历史压成摘要，让后续每轮都少付这份 token。
 * <p>
 * <b>与 {@code ContextWindow} 的分工</b>：那个是「只裁本次请求」的机械裁剪，历史一条不动；
 * 本类是「多花一次调用换长期便宜」，结果是留下<b>摘要 + 新边界</b>。两者互补，不互相替代——
 * 而且触发判据刻意同源（同一个 {@link TokenEstimator}、同一个预算公式），否则会出现「压缩觉得
 * 还很宽裕、裁剪已经开始丢历史」这种互相打架的状态。
 * <p>
 * <b>谁会触发它</b>：用户敲 {@code /compact}（手动），或内核在组装每轮请求时发现上下文用量已到
 * {@code react.autoCompactPercent}（缺省 80%）、或者<b>本次请求已经发生了机械裁剪</b>（自动）。
 * 后者比前者更该压：它意味着历史正在静默丢失。
 * <p>
 * <b>为什么自带执行器</b>：{@code /compact} 是在渲染线程上被敲出来的，而摘要调用是一次完整的
 * 同步 HTTP 往返——在渲染线程上做，界面会整个卡住，用户除了等着什么也做不了。因此照
 * {@code ReActLooper} 的形态自持一个小型守护线程池（线程名 {@code compact}），
 * 命令只负责「起」，结果由外壳轮询 {@link #status(String)} 取。自动压缩同理由此，且它跑在
 * 本轮模型调用<b>旁边</b>：请求已经构造好了，压缩不会拖慢这一轮。
 * <p>
 * <b>为什么不做流式</b>：摘要没有「让人边看边等」的价值，它是一份中间产物；流式只会多出一套
 * 增量拼接与取消的账。
 * <p>
 * <b>一次压完，装不下就丢最旧的</b>：摘要请求本身也受同一个上下文窗口约束，而「要压的东西大到
 * 发不出去」恰恰是需要压缩的原因。因此待压范围超过预算时，从<b>最旧侧丢弃</b>到装得下为止，
 * 只把最新的一段交给模型——一次命令一次调用，耗时与花费都可预期。
 * 被丢弃的那一段既不在摘要里也不再进请求，是真正消失的数据，因此它的条数会被<b>如实上报并随会话落盘</b>
 * （否则「模型为什么忘了」与「这次压缩付了什么代价」都会变成查不出来的谜）。
 * <p>
 * <b>滚动摘要</b>：新摘要把上一份摘要一起喂进模型合并，因此会话里始终只有一块摘要、边界只能向后移。
 * <p>
 * <b>插件只定策略</b>：{@code CompactionStrategyRequest} 让插件对「这一次该怎么压」表态（摘要措辞、
 * 保留多少原文、摘要多长），读消息、选范围、发调用、改边界全在本类手里。插件拿不到任何一条消息正文。
 *
 * @author zcd
 */
@Singleton
public class ConversationCompactor implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConversationCompactor.class);

    /** compact 执行器线程数：压缩是重活，不并发，免得与正常回合抢模型配额。 */
    private static final int COMPACT_MAX_THREADS = 2;

    /** compact 执行器等待队列容量。 */
    private static final int COMPACT_QUEUE_CAPACITY = 16;

    /** compact 线程空闲回收时间（秒）。 */
    private static final long COMPACT_KEEP_ALIVE_SECONDS = 60L;

    /** 摘要输入里旧摘要块的表头。 */
    private static final String OLD_SUMMARY_HEADER = "【已有摘要（需要与下面的新内容合并）】";

    /** 摘要输入里新增历史块的表头。 */
    private static final String NEW_HISTORY_HEADER = "【新增对话历史】";

    /** 摘要被本地截断时追加的标记。 */
    private static final String SUMMARY_TRUNCATED_MARKER = "\n…（摘要超长，已截断）";

    /** 会话域服务：读会话、写回压缩结果与用量。 */
    private final SessionManager sessionManager;

    /** 模型门面：给出客户端。 */
    private final ModelManager modelManager;

    /** 会话模型解析器：与对话共用同一套三级回落。 */
    private final SessionModelResolver sessionModelResolver;

    /** 运行时配置门面：读取压缩参数与上下文预留。 */
    private final RuntimeConfig runtimeConfig;

    /** 同步扩展点策略：插件压缩策略的唯一来源。 */
    private final ExtensionRegistry extensions;

    /**
     * 提示词组装器：用来造 cache-safe fork 请求（见 {@code PromptAssembler#buildFork}）。
     * <p>
     * <b>这里为什么需要它</b>：fork 要求把父请求发出去的那串字节原样复用，而「父请求长什么样」
     * 只有组装器知道（摘要合成消息、工具结果老化、机械裁剪、工具清单都在那里做）。
     * 压缩机自己再拼一份，两端一定会漂。
     */
    private final PromptAssembler promptAssembler;

    /** 通知发布入口：只在「该压了却没有插件」时广播一条告警。 */
    private final EventPublisher events;

    /** 专用执行器。 */
    private final ExecutorService executor;

    /** 每会话压缩状态，供外壳轮询。 */
    private final Map<String, State> states = new ConcurrentHashMap<String, State>();

    /** 是否已经就「没有压缩插件」提醒过一次，避免每轮组装都刷同一条告警。 */
    private final AtomicBoolean availabilityWarned = new AtomicBoolean(false);

    /**
     * 构造压缩器并创建专用执行器。
     *
     * @param sessionManager 会话域服务
     * @param modelManager   模型门面
     * @param runtimeConfig  运行时配置门面
     * @param extensions     同步扩展点策略
     * @param events         通知发布入口
     * @param sessionModelResolver 会话模型解析器
     * @param promptAssembler 提示词组装器（造 cache-safe fork 请求）
     */
    @Inject
    public ConversationCompactor(SessionManager sessionManager, ModelManager modelManager,
                                 RuntimeConfig runtimeConfig, ExtensionRegistry extensions,
                                 EventPublisher events, SessionModelResolver sessionModelResolver,
                                 PromptAssembler promptAssembler) {
        this(sessionManager, modelManager, runtimeConfig, extensions, events, sessionModelResolver,
                promptAssembler, createExecutor());
    }

    /**
     * 测试用构造器：注入执行器以便控制压缩线程。
     *
     * @param sessionManager 会话域服务
     * @param modelManager   模型门面
     * @param runtimeConfig  运行时配置门面
     * @param extensions     同步扩展点策略
     * @param events         通知发布入口
     * @param sessionModelResolver 会话模型解析器
     * @param promptAssembler 提示词组装器（造 cache-safe fork 请求）
     * @param executor       专用执行器
     */
    ConversationCompactor(SessionManager sessionManager, ModelManager modelManager, RuntimeConfig runtimeConfig,
                          ExtensionRegistry extensions, EventPublisher events,
                          SessionModelResolver sessionModelResolver, PromptAssembler promptAssembler,
                          ExecutorService executor) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.sessionModelResolver = Objects.requireNonNull(sessionModelResolver,
                "sessionModelResolver must not be null");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * 压缩功能是否可用：有没有插件为压缩策略登记了处理器。
     * <p>
     * <b>只查注册表，不执行 handler</b>：这是外壳每帧、每轮组装都可能问的问题（状态栏、{@code /status}、
     * 每轮自动压缩的判据），不能变成一次插件调用。代价是它只知道「有没有人应答」，不知道「应答是否可用」
     * ——「有处理器但摘要指令是空的」这种坏插件由真正发起压缩时抛出
     * {@link CompactionUnavailableException} 兜底。
     * <p>
     * <b>为什么可用性取决于插件</b>：摘要指令由插件提供（内核只提供机制）。没有插件 → 没有指令 →
     * 没有可发给模型的摘要请求，因此这里返回 {@code false} 时压缩是<b>整体缺席</b>，而不是回退到某个
     * 内置策略。
     *
     * @return 有插件提供压缩策略返回 {@code true}
     */
    public boolean isAvailable() {
        return !extensions.bindings(CompactionStrategyRequest.class, null).isEmpty();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 取某会话的压缩状态。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 状态，保证非 {@code null}；从未压缩过时为 {@link Status#IDLE}
     */
    public State status(String sessionId) {
        if (sessionId == null) {
            return State.idle();
        }
        State state = states.get(sessionId);
        return state == null ? State.idle() : state;
    }

    /**
     * 生成一次压缩计划，不发起任何模型调用。
     * <p>
     * 待压范围是「上次边界之后、再留出保留条数」的那一段：
     * <ul>
     *     <li>整段装得进一次摘要请求 → 全压，边界推进到保留段之前；</li>
     *     <li>装不进 → 从最旧侧丢弃，只把最新的一段交给模型（至少一条），
     *     边界仍然推进到保留段之前，被丢弃的条数记在计划里。</li>
     * </ul>
     *
     * @param sessionId 会话标识，不可为空白
     * @return 压缩计划；没有足够历史可压时返回 {@code null}
     * @throws CompactionUnavailableException 没有任何插件给出可用的摘要策略时抛出
     * @throws JellyfishException             会话不存在或模型解析失败时抛出
     */
    public CompactionPlan plan(String sessionId) {
        return planOf(sessionManager.require(sessionId), CompactionTrigger.MANUAL);
    }

    /**
     * 起一次异步压缩：本方法只做校验与派发，立即返回。
     * <p>
     * <b>校验放在调用点线程</b>：会话不存在、没有可压的历史、已有压缩在进行——这三种都是用户当场
     * 就该看到的问题，放进异步任务会让用户以为「开始了」，然后收到一条失败提示。
     *
     * @param sessionId 会话标识，不可为空白
     * @param trigger   触发原因，不可为 {@code null}
     * @throws CompactionUnavailableException 没有任何插件给出可用的摘要策略时抛出
     * @throws JellyfishException             会话不存在、没有足够历史、已有压缩在进行，或模型解析失败时抛出
     */
    public void start(String sessionId, CompactionTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger must not be null");
        CompactionPlan plan = planOf(sessionManager.require(sessionId), trigger);
        if (plan == null) {
            throw new JellyfishException("没有足够的历史可压缩");
        }
        dispatch(sessionId, plan, trigger);
    }

    /**
     * 自动压缩：上下文用量到阈值、或本次请求已经发生机械裁剪时，起一次压缩。
     * <p>
     * <b>为什么「已被裁剪」也要压</b>：阈值是预防，裁剪是既成事实。{@code ContextWindow} 已经在丢
     * 最旧的历史了，那一刻不压，丢掉的细节就永远回不来——此时该压的理由比到 80% 更充分。
     * <p>
     * <b>为什么静默</b>：本方法在组装每轮请求的路径上被调用，任何异常都不该让一轮对话发不出去；
     * 而且「没有足够历史」「已有压缩在跑」在自动触发下都不是错误，只是时机不对。
     * <p>
     * <b>没装压缩插件时让路，但只提醒一次</b>：功能缺席不该每轮刷一条日志，也不该彻底无声——
     * 上下文到 80% 却压不了，意味着接下来的每一轮都在靠机械裁剪丢历史。因此第一次真正用得上却用不了时
     * 记一条 WARN 并广播 {@code ConfigWarningEvent}，之后不再重复。
     * <p>
     * <b>不会反复花钱</b>：被拒绝 / 无可压时都不发请求；真压成功必推进边界，等保留的尾巴兜住全局
     * （{@link #planOf} 返回 {@code null}）时自然停下。
     *
     * @param sessionId 会话标识，不可为空白
     * @param usage     本轮请求的上下文用量，不可为 {@code null}
     * @return 真的起了压缩返回 {@code true}
     */
    public boolean autoCompactIfNeeded(String sessionId, ContextUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        int percent = reactSettings().getAutoCompactPercent();
        if (percent <= 0 || (!usage.exceeds(percent) && !usage.isTruncated())) {
            return false;
        }
        if (!isAvailable()) {
            warnUnavailable();
            return false;
        }
        try {
            CompactionPlan plan = planOf(sessionManager.require(sessionId), CompactionTrigger.AUTO);
            if (plan == null) {
                return false;
            }
            dispatch(sessionId, plan, CompactionTrigger.AUTO);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("自动压缩未启动: sessionId={} reason={}", sessionId, messageOf(e));
            return false;
        }
    }

    /**
     * 派发一次压缩：占住状态机并把任务交给执行器。
     *
     * @param sessionId 会话标识
     * @param plan      压缩计划
     * @param trigger   触发原因
     * @throws JellyfishException 已有压缩在进行，或执行器队列已满时抛出
     */
    private void dispatch(String sessionId, CompactionPlan plan, CompactionTrigger trigger) {
        // compute 在同一把锁内完成「查状态 + 置 RUNNING」：两个并发的 /compact 只有一个能挤进去
        states.compute(sessionId, (key, current) -> {
            if (current != null && current.getStatus() == Status.RUNNING) {
                throw new JellyfishException("压缩已在进行中，请等待当前压缩结束。");
            }
            return State.running(trigger);
        });
        try {
            executor.execute(() -> run(sessionId, plan, trigger));
        } catch (RejectedExecutionException e) {
            // 队列满时必须把状态复原：留着 RUNNING 会让这个会话之后每一次 /compact 都被告知
            // 「已在进行中」，而那个「进行中」的压缩根本不存在
            states.put(sessionId, State.failed("压缩任务队列已满，请稍后重试", trigger));
            throw new JellyfishException("压缩任务队列已满，请稍后重试", e);
        }
    }

    /**
     * 执行一次压缩：发摘要请求 → 记用量 → 应用压缩结果，任何失败都收敛成 {@link Status#FAILED}。
     * <p>
     * <b>失败不改变会话</b>：只有摘要请求成功且拿到非空摘要时才会推进边界。失败时旧边界与旧摘要原样保留，
     * 下一轮请求照旧——宁可什么都没发生，也不要推进到一个「半个摘要」的状态。
     *
     * @param sessionId 会话标识
     * @param plan      压缩计划
     * @param trigger   触发原因
     */
    private void run(String sessionId, CompactionPlan plan, CompactionTrigger trigger) {
        try {
            ResolvedModel resolvedModel = resolveModel(sessionManager.require(sessionId));
            LlmResponse response;
            try {
                response = clientOf(resolvedModel).chat(plan.getRequest());
            } catch (JellyfishException e) {
                // 只报这一句：下面的 catch 还会接住「边界消息不在会话里」这类内部错误，
                // 它们不是模型调用失败，混进同一条事件会让订阅方误以为端点拒了请求
                sessionManager.publishCallFailure(sessionId, resolvedModel.getModel().getId(), e);
                throw e;
            }
            // 用量先记：请求已经发出去了，token 就花掉了——哪怕摘要在下面被判定为不可用
            if (response.getUsage() != null) {
                sessionManager.recordUsage(sessionId, response.getUsage());
            }
            String summary = requireSummary(response);
            sessionManager.applyCompaction(sessionId, summary, plan.getBoundaryMessageId(), plan.getDroppedCount());
            states.put(sessionId, State.done(doneMessage(plan, summary), trigger));
            LOG.info("会话压缩完成: sessionId={} trigger={} compressed={} dropped={} keep={}", sessionId,
                    trigger, plan.getCompressedCount(), plan.getDroppedCount(), plan.getKeepCount());
        } catch (RuntimeException e) {
            LOG.warn("会话压缩失败: sessionId={} trigger={}", sessionId, trigger, e);
            states.put(sessionId, State.failed(messageOf(e), trigger));
        }
    }

    /**
     * 生成压缩计划（纯逻辑，不含任何远程调用）。
     *
     * @param session 会话运行态
     * @param trigger 触发原因
     * @return 压缩计划；没有足够历史可压时返回 {@code null}
     * @throws CompactionUnavailableException 合并后的摘要指令为空时抛出
     * @throws JellyfishException             模型解析失败时抛出
     */
    private CompactionPlan planOf(Session session, CompactionTrigger trigger) {
        List<SessionMessage> messages = session.getMessages();
        SessionCompaction previous = effectiveCompaction(session);
        // 起点在旧边界之后：已压的那一段不进请求，重压它只是再花一次钱
        int from = previous == null ? 0 : session.indexOfMessage(previous.getBoundaryMessageId()) + 1;
        // 模型解析做成「尽力而为」：下面那个「有没有可压的历史」的判断不该依赖模型是否能解析出来，
        // 否则一台没配模型的机器上 /compact preview 会报「没有可用模型」而不是「没什么可压」
        ResolvedModel resolvedModel = resolveModelOrNull(session);
        CompactionStrategy strategy = strategyOf(session, trigger, from, messages.size(),
                resolvedModel == null ? 0L : inputBudget(resolvedModel),
                resolvedModel == null ? null : resolvedModel.getModel().getId());
        if (StringUtils.isBlank(strategy.getSummaryPrompt())) {
            // 没有摘要指令就没有可以发给模型的请求。放在「有没有可压的历史」之前判断：
            // 「没装压缩插件」和「没什么可压」对用户是两件事，前者该说清楚是功能缺失
            throw new CompactionUnavailableException(availabilityMessage(session));
        }
        int keepRecent = keepRecentOf(strategy, messages.size());
        // 压缩前钩子：位置是本条链上唯一「还没花钱」的点。它必须排在范围选定之前，
        // 因为插件能改保留条数；排在范围之后就只能否决，不能表达「少压一点」。
        // 装配顺序也顺带说明了为什么不能挪到 run() 里：那时摘要请求已经发出去了
        CompactionDirective directive = compactionDirective(session.getSessionId(), trigger, messages, from,
                keepRecent, previous == null ? null : previous.getBoundaryMessageId());
        if (directive.isCancelled()) {
            throw new JellyfishException("压缩被插件拦下：" + reasonOf(directive.getReason()));
        }
        if (directive.hasKeepRecent()) {
            // 与策略给出的值同一套钳制：插件写出荒谬的值不该让压缩失控
            keepRecent = Math.max(0, Math.min(directive.getKeepRecent().intValue(), messages.size()));
        }
        int end = alignToToolGroup(messages, messages.size() - keepRecent, from);
        if (end <= from) {
            return null;
        }
        // 走到这里非压不可，模型解析失败才是真的失败
        resolvedModel = resolvedModel == null ? resolveModel(session) : resolvedModel;
        int maxSummaryChars = maxSummaryCharsOf(strategy);
        String instructions = renderInstructions(strategy.getSummaryPrompt(), maxSummaryChars);
        String boundaryMessageId = messages.get(end - 1).getMessageId();
        // 保留段实际有多少条：向后对齐工具调用组时 end 退过，因此这里可能多于 keepRecent
        int retained = messages.size() - end;
        // 首选 cache-safe fork：复用父请求的前缀，因此整段对话按命中价计费，
        // 只有末尾那条指令是新的。它必须原样带上工具，所以同时要把工具调用关掉
        LlmRequest fork = promptAssembler.buildFork(session, resolvedModel, from, retained, instructions);
        if (fork != null) {
            // 没有丢弃可言：发出去的正是父请求刚发过、且装得下的那一段
            return new CompactionPlan(fork, boundaryMessageId, end - from, 0, keepRecent,
                    TokenEstimator.estimate(instructions), true);
        }
        // 回退：待压范围已被机械裁剪吞掉，fork 出来的前缀会缺一段内容。
        // 此时只能把该段渲染成正文发出去——按全价，但摘要至少建立在完整材料上。
        // 这条路正常只出现在「用量已经超预算、裁剪正在丢历史」的现场，因此保留为后台而非常态
        String head = headerOf(previous);
        long bodyBudget = inputBudget(resolvedModel) - TokenEstimator.estimate(instructions)
                - TokenEstimator.estimate(head);
        int start = selectStart(messages, from, end, bodyBudget);
        String body = head + renderMessages(messages, start, end - 1);
        LlmRequest.Builder builder = LlmRequest.builder(resolvedModel.getModel().getId())
                .systemPrompt(instructions)
                .messages(Collections.singletonList(LlmMessage.user(body)));
        int modelMaxOutput = resolvedModel.getModel().getMaxOutputTokens();
        if (modelMaxOutput > 0) {
            builder.maxTokens(modelMaxOutput);
        }
        return new CompactionPlan(builder.build(), boundaryMessageId, end - start,
                start - from, keepRecent, TokenEstimator.estimate(body), false);
    }

    /**
     * 把「保留段起点」向前退到工具调用组的开头，使边界不掰开 {@code assistant(toolCalls)} 与其工具结果。
     * <p>
     * <b>为什么必须对齐</b>：边界是按<b>条数</b>算出来的，而 {@code tool} 消息在 ReAct 会话里占相当比例，
     * 因此边界很容易正好落在 {@code assistant(toolCalls)} 与它的工具结果之间。这时发送序列会以一条
     * 孤儿 {@code tool} 消息开头（它的 {@code assistant} 被边界切掉了），厂商会以 400 拒掉整次请求，
     * 而该会话在边界下一次推进之前<b>每一次请求都会失败</b>。约束的完整说明见 {@link ToolPairing}。
     * <p>
     * <b>为什么向后退而不是向前跳</b>：向前跳要连工具结果一起丢掉，向后退只是多保留一组——
     * {@code keepRecent} 的语义是「<b>至少</b>保留最近多少条原文」，多留一组并不违背它，
     * 而且能保住「刚才做过什么」这段最可能被接着用到的上下文。
     *
     * @param messages 会话消息列表，不可为 {@code null}
     * @param end      原始起点下标（第一条要保留的消息）
     * @param from     可压范围的下界（含）；退到它以下就没有可压的历史了
     * @return 对齐后的起点下标；可能等于 {@code from}（表示没有可压的历史）
     */
    private static int alignToToolGroup(List<SessionMessage> messages, int end, int from) {
        int aligned = end;
        // aligned < messages.size() 同时挡住 keepRecent 为 0 时 end == size 的越界
        while (aligned > from && aligned < messages.size()
                && ToolPairing.isToolResult(messages.get(aligned).getMessage())) {
            aligned--;
        }
        return aligned;
    }

    /**
     * 取进摘要的第一条消息下标：从最新一条往回装，装不下的更旧消息直接丢弃。
     * <p>
     * <b>为什么至少取一条</b>：一条都不进摘要，就会得到「边界前移了、摘要却没变」的状态——最难排查的
     * 组合。若单条消息本身就超过整个窗口，照发不误，让模型调用的失败信息来说明问题（那比「什么都没压」
     * 更接近事实）。
     * <p>
     * <b>为什么从新到旧而不是从旧到新</b>：装不下时丢的是<b>最旧</b>的那一端。反过来会让「最新的一条」
     * 被丢掉——那恰好是模型最可能接着用到的上下文。
     *
     * @param messages 会话消息列表
     * @param from     起点下标（含）
     * @param end      终点下标（不含）
     * @param budget   摘要正文的 token 预算，未配窗口时为 {@link Long#MAX_VALUE}
     * @return 进摘要的第一条消息下标，保证落在 {@code [from, end)} 内
     */
    private static int selectStart(List<SessionMessage> messages, int from, int end, long budget) {
        int start = end - 1;
        long used = TokenEstimator.estimateMessage(messages.get(start).getMessage());
        for (int index = end - 2; index >= from; index--) {
            int cost = TokenEstimator.estimateMessage(messages.get(index).getMessage());
            if (used + cost > budget) {
                break;
            }
            used += cost;
            start = index;
        }
        return start;
    }

    /**
     * 询问各插件本次的压缩策略，并按 order 合并。
     * <p>
     * <b>合并规则</b>：逐字段取 <b>order 最小且声明了该字段</b> 的那一个——即「按优先级取第一」而不是
     * 拼接或取极值。拼接会拼出一份谁也没写过的摘要指令，取极值在不同字段上有不同的正确方向，讲不清也难测。
     * 只声明数值、不写指令的插件是合法的（它只调参数），只要另有插件提供指令。
     * <p>
     * <b>单个处理器失败只记告警并跳过</b>：与提示词贡献同口径。压缩策略是锦上添花，一个坏插件不该
     * 让别的插件也失效。
     *
     * @param session      会话运行态
     * @param trigger      触发原因
     * @param compressedCount 已被摘要覆盖的条数
     * @param messageCount 消息总条数
     * @param budgetTokens 摘要输入的 token 预算；{@code 0} 表示模型还没解析出来
     * @param modelId      会话当前模型标识，可为 {@code null}（模型未解析出来时）
     * @return 合并后的策略，保证非 {@code null}；没有插件登记处理器时为 {@link CompactionStrategy#none()}
     */
    private CompactionStrategy strategyOf(Session session, CompactionTrigger trigger, int compressedCount,
                                          int messageCount, long budgetTokens, String modelId) {
        List<HandlerBinding<CompactionStrategyRequest, CompactionStrategy>> bindings =
                extensions.bindings(CompactionStrategyRequest.class, null);
        if (bindings.isEmpty()) {
            return CompactionStrategy.none();
        }
        // 同一个请求对象复用给全部处理器：载荷只有数字与标识，处理器只读
        CompactionStrategyRequest request = new CompactionStrategyRequest(session.getSessionId(), trigger,
                messageCount, compressedCount, reactSettings().getCompactKeepRecentMessages(),
                reactSettings().getCompactMaxSummaryChars(), budgetTokens, modelId);
        String prompt = null;
        Integer keepRecent = null;
        Integer maxSummaryChars = null;
        for (HandlerBinding<CompactionStrategyRequest, CompactionStrategy> binding : bindings) {
            CompactionStrategy strategy = strategyOf(binding, request);
            if (strategy == null) {
                continue;
            }
            if (prompt == null && StringUtils.isNotBlank(strategy.getSummaryPrompt())) {
                prompt = strategy.getSummaryPrompt().trim();
            }
            if (keepRecent == null) {
                keepRecent = strategy.getKeepRecentMessages();
            }
            if (maxSummaryChars == null) {
                maxSummaryChars = strategy.getMaxSummaryChars();
            }
        }
        return new CompactionStrategy(prompt, keepRecent, maxSummaryChars);
    }

    /**
     * 渲染摘要指令：把长度上限填进占位符。
     * <p>
     * <b>缺占位符只告警、不失败</b>：摘要指令现在是插件自带的资源，要求每个插件作者都记得写占位符，
     * 会增加一个「指令写得挺好、只是没写占位符」就整功能不可用的失败面；而缺了它也只是模型少一条自我
     * 约束——内核对超长摘要本来就有本地截断兜底（见 {@code truncate}）。压缩是低频操作，每次一条 WARN
     * 不会刷屏。
     *
     * @param prompt          摘要指令正文，保证非空白（调用点已校验）
     * @param maxSummaryChars 本次生效的摘要长度上限
     * @return 可直接作为 system prompt 的指令文本
     */
    private static String renderInstructions(String prompt, int maxSummaryChars) {
        if (!prompt.contains(CompactionStrategy.MAX_CHARS_PLACEHOLDER)) {
            LOG.warn("压缩摘要指令缺少占位符 {}，模型不会被告知长度上限（内核仍会本地截断）",
                    CompactionStrategy.MAX_CHARS_PLACEHOLDER);
        }
        return prompt.replace(CompactionStrategy.MAX_CHARS_PLACEHOLDER, String.valueOf(maxSummaryChars));
    }

    /**
     * 组装「压缩为什么不可用」的用户可见文案。
     * <p>
     * 区分「一个插件都没有」与「有插件但没给出指令」：前者要装插件，后者要查那个插件为什么什么都没说——
     * 提示词里那句「没有可用的压缩策略」在两种情况下都成立，但修复动作完全不同。
     * <p>
     * 文案里不写具体插件 id：内核不该认识任何一个官方插件的名字，否则换一份发行版就成了谎言。
     *
     * @param session 会话运行态
     * @return 用户可见的失败原因
     */
    private String availabilityMessage(Session session) {
        if (!isAvailable()) {
            return "没有插件提供压缩策略（压缩由插件决定，内核只负责执行），请先安装并启用压缩插件";
        }
        LOG.warn("压缩策略处理器存在但未给出摘要指令: sessionId={}", session.getSessionId());
        return "压缩插件没有给出摘要指令，无法组装摘要请求（检查它的配置与资源）";
    }

    /**
     * 提醒「上下文已经该压了，但没有插件提供压缩策略」——整进程只提醒一次。
     * <p>
     * 提醒是必要的：自动压缩缺席时用户看到的一切都正常，只是历史在靠机械裁剪静默变少；
     * 但它发生在每轮组装的路径上，重复提醒会把日志和界面刷满。
     */
    private void warnUnavailable() {
        if (!availabilityWarned.compareAndSet(false, true)) {
            return;
        }
        LOG.warn("上下文已接近预算，但没有插件提供压缩策略，自动压缩不生效（历史将依赖机械裁剪）");
        events.publish(new ConfigWarningEvent("compaction",
                "没有插件提供压缩策略，自动压缩不生效；安装压缩插件后可用 /compact 手动压缩"));
    }

    /**
     * 执行单个策略处理器并取出策略。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 策略请求
     * @return 策略；处理失败时返回 {@code null}
     */
    private CompactionStrategy strategyOf(HandlerBinding<CompactionStrategyRequest, CompactionStrategy> binding,
                                          CompactionStrategyRequest request) {
        try {
            return extensions.invoke(binding.getHandler(), request);
        } catch (Exception e) {
            LOG.warn("压缩策略处理器执行失败，已跳过: owner={} reason={}", binding.getOwner(), e.getMessage());
            return null;
        }
    }

    /**
     * 取生效的保留条数：插件策略优先于配置，并钳制到合法区间。
     * <p>
     * <b>为什么要钳制</b>：保留条数小于 0 无意义、大于消息总数等于「什么都没压」。插件写出荒谬的值
     * 不该让压缩失控——这是内核对自己保命机制的把关，不是对插件的不信任。
     *
     * @param strategy     插件策略
     * @param messageCount 消息总条数
     * @return 保留条数，落在 {@code [0, messageCount]}
     */
    private int keepRecentOf(CompactionStrategy strategy, int messageCount) {
        Integer declared = strategy.getKeepRecentMessages();
        int keepRecent = declared == null ? reactSettings().getCompactKeepRecentMessages() : declared;
        return Math.max(0, Math.min(keepRecent, messageCount));
    }

    /**
     * 跑一遍压缩前钩子链，返回最终指令。
     * <p>
     * <b>链式语义</b>（写在调用点的 {@code for} 循环里，注册表不参与）：
     * 第一个 {@code cancel} 立即短路（理由取自它）；{@code keepRecent} 取<b>最后一个非缺省</b>值。
     * <p>
     * <b>失败语义是「放行」</b>：同步派发没有护栏，异常处置是本方法的责任；插件坏掉不该让压缩
     * 彻底不能用，也不该静默改掉保留条数。
     * <p>
     * <b>无插件时不构造任何请求对象</b>：token 估算要把整段历史过一遍，只有真要问插件时才付这个代价。
     *
     * @param sessionId  会话标识
     * @param trigger    触发原因
     * @param messages   当前会话的全部消息
     * @param from       待压缩范围的起点（旧边界之后）
     * @param keepRecent 策略算完后的保留条数
     * @param previousBoundaryMessageId 旧边界消息标识，可为 {@code null}
     * @return 最终指令；链上没有可用处理器时为 {@link CompactionDirective#proceed()}
     */
    private CompactionDirective compactionDirective(String sessionId, CompactionTrigger trigger,
                                                    List<SessionMessage> messages, int from, int keepRecent,
                                                    String previousBoundaryMessageId) {
        List<ExtensionHandler<CompactionPreRequest, CompactionDirective>> handlers =
                extensions.handlers(CompactionPreRequest.class, null);
        if (handlers.isEmpty()) {
            return CompactionDirective.proceed();
        }
        int tokensBefore = estimateTokens(messages, from);
        Integer override = null;
        for (ExtensionHandler<CompactionPreRequest, CompactionDirective> handler : handlers) {
            CompactionDirective directive;
            try {
                directive = extensions.invoke(handler, new CompactionPreRequest(sessionId, trigger, messages.size(),
                        tokensBefore, keepRecent, previousBoundaryMessageId));
            } catch (RuntimeException e) {
                LOG.warn("压缩前处理器抛错，按放行处理", e);
                continue;
            }
            if (directive == null) {
                continue;
            }
            if (directive.isCancelled()) {
                return directive;
            }
            if (directive.hasKeepRecent()) {
                override = directive.getKeepRecent();
            }
        }
        return override == null ? CompactionDirective.proceed() : CompactionDirective.keepRecent(override.intValue());
    }

    /**
     * 估算一段历史消息的 token 数。
     * <p>
     * <b>估算而不是实测</b>：按文本长度折算，不含 system prompt 与工具定义。它只用于给插件一个
     * 「现在多大了」的参考，因此精度够用；不要拿它与厂商返回的计费数对上。
     *
     * @param messages 全部消息
     * @param from     起点下标
     * @return token 估算值
     */
    private static int estimateTokens(List<SessionMessage> messages, int from) {
        int start = Math.max(0, Math.min(from, messages.size()));
        List<LlmMessage> history = new ArrayList<LlmMessage>(messages.size() - start);
        for (int index = start; index < messages.size(); index++) {
            history.add(messages.get(index).getMessage());
        }
        return TokenEstimator.estimateMessages(history);
    }

    /**
     * 取生效的摘要长度上限：插件策略优先于配置，并钳制到合法区间。
     *
     * @param strategy 插件策略
     * @return 字符数上限
     */
    private int maxSummaryCharsOf(CompactionStrategy strategy) {
        Integer declared = strategy.getMaxSummaryChars();
        int maxSummaryChars = declared == null ? reactSettings().getCompactMaxSummaryChars() : declared;
        return Math.max(ReactSettings.MIN_COMPACT_MAX_SUMMARY_CHARS,
                Math.min(maxSummaryChars, ReactSettings.MAX_COMPACT_MAX_SUMMARY_CHARS));
    }

    /**
     * 取生效的压缩摘要：边界消息必须仍然在会话里。
     * <p>
     * <b>边界缺失时整份压缩记录失效</b>（只可能来自手工改过的会话文件）：既不能按它截断
     * （不猜位置），也不能把它的摘要喂给模型（那段摘要覆盖的是哪一段已经无从判断，而下面的输入
     * 又是整段历史——两者会互相矛盾）。退回「从未压缩过」只是多花一次摘要的钱。
     *
     * @param session 会话运行态
     * @return 生效的压缩摘要；没有或已失效时返回 {@code null}
     */
    private static SessionCompaction effectiveCompaction(Session session) {
        SessionCompaction compaction = session.getCompaction();
        if (compaction == null) {
            return null;
        }
        if (session.indexOfMessage(compaction.getBoundaryMessageId()) < 0) {
            LOG.warn("压缩边界消息不在会话里，按未压缩处理: sessionId={} boundary={}",
                    session.getSessionId(), compaction.getBoundaryMessageId());
            return null;
        }
        return compaction;
    }

    /**
     * 把待压消息渲染成给模型看的对话文本。
     *
     * @param messages 会话消息列表
     * @param from     起点下标（含）
     * @param to       终点下标（含）
     * @return 渲染文本，保证非 {@code null}
     */
    private static String renderMessages(List<SessionMessage> messages, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int index = from; index <= to; index++) {
            text.append(renderMessage(messages.get(index)));
        }
        return text.toString();
    }

    /**
     * 渲染单条消息：角色标签 + 正文，工具调用渲染成一行。
     * <p>
     * 刻意带上角色与工具名：摘要要能说清「谁说的、工具看到了什么」，只给正文会让
     * 「模型说过的话」与「工具返回的内容」在摘要里混成一份不可信的东西。
     *
     * @param message 会话消息
     * @return 渲染文本，空消息返回空串
     */
    private static String renderMessage(SessionMessage message) {
        LlmMessage body = message.getMessage();
        StringBuilder text = new StringBuilder();
        if (StringUtils.isNotBlank(body.getContent())) {
            text.append('[').append(roleLabel(body)).append("]\n").append(body.getContent()).append('\n');
        }
        for (LlmToolCall toolCall : body.getToolCalls()) {
            text.append("[工具调用] ").append(toolCall.getName()).append('(')
                    .append(toolCall.getArguments()).append(")\n");
        }
        return text.toString();
    }

    /**
     * 把角色映射成中文标签。
     *
     * @param message 消息
     * @return 标签
     */
    private static String roleLabel(LlmMessage message) {
        String role = message.getRole();
        if (LlmMessage.ROLE_USER.equals(role)) {
            return "用户";
        }
        if (LlmMessage.ROLE_ASSISTANT.equals(role)) {
            return "助手";
        }
        if (LlmMessage.ROLE_TOOL.equals(role)) {
            return StringUtils.isBlank(message.getName())
                    ? "工具结果" : "工具结果 " + message.getName();
        }
        if (LlmMessage.ROLE_SYSTEM.equals(role)) {
            return "系统";
        }
        return role;
    }

    /**
     * 组装摘要输入里「旧摘要 + 新历史」的表头部分。
     *
     * @param compaction 生效的旧压缩摘要，可为 {@code null}
     * @return 表头文本
     */
    private static String headerOf(SessionCompaction compaction) {
        StringBuilder head = new StringBuilder();
        if (compaction != null) {
            head.append(OLD_SUMMARY_HEADER).append('\n').append(compaction.getSummary()).append('\n');
        }
        return head.append(NEW_HISTORY_HEADER).append('\n').toString();
    }

    /**
     * 取本次摘要输入的 token 预算。
     * <p>
     * 与 {@code PromptAssembler} 同一套口径：模型没配上下文窗口时不设上限（宁可让厂商报错，
     * 也好过我们自己猜一个数把历史丢掉）。
     *
     * @param resolvedModel 已解析的模型
     * @return 预算 token 数，未配窗口时为 {@link Long#MAX_VALUE}
     */
    private long inputBudget(ResolvedModel resolvedModel) {
        Model model = resolvedModel.getModel();
        int contextLength = model.getContextLength();
        if (contextLength <= 0) {
            return Long.MAX_VALUE;
        }
        long budget = (long) contextLength - model.getMaxOutputTokens() - reactSettings().getContextReserveTokens();
        return Math.max(1L, budget);
    }

    /**
     * 校验并取回摘要正文，超长时本地截断。
     *
     * @param response 模型响应
     * @return 摘要正文，保证非空白
     * @throws JellyfishException 模型没有返回摘要时抛出
     */
    private String requireSummary(LlmResponse response) {
        String summary = response == null ? null : response.getContent();
        if (StringUtils.isBlank(summary)) {
            throw new JellyfishException("模型没有返回摘要");
        }
        String trimmed = summary.trim();
        int maxChars = reactSettings().getCompactMaxSummaryChars();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxChars) {
            return trimmed;
        }
        // 按码点截断：在代理对中间切开会产生一个无法解码的半字符，落盘后读回来就是乱码
        int end = trimmed.offsetByCodePoints(0, maxChars);
        return trimmed.substring(0, end) + SUMMARY_TRUNCATED_MARKER;
    }

    /**
     * 组装完成提示。
     * <p>
     * <b>不在这里写「自动 / 手动」</b>：触发原因已经随状态一起给了外壳，让它按自己的措辞渲染
     * （否则会出现「自动压缩：自动压缩：已压缩 12 条」这种两层前缀）。这里只把事实说全。
     * <p>
     * <b>丢弃条数要写出来</b>：那部分历史是真的没了（不在摘要里、也不会再发给模型），
     * 这属于「用户有权知道的代价」，不能只留在日志里。
     *
     * @param plan    压缩计划
     * @param summary 摘要正文
     * @return 提示文本
     */
    private static String doneMessage(CompactionPlan plan, String summary) {
        StringBuilder text = new StringBuilder();
        text.append("已压缩 ").append(plan.getCompressedCount())
                .append(" 条更早的消息（摘要 ").append(summary.length()).append(" 字，保留最近 ")
                .append(plan.getKeepCount()).append(" 条原文）");
        if (plan.hasDropped()) {
            text.append("。另有 ").append(plan.getDroppedCount())
                    .append(" 条更早的消息超出摘要输入预算，未纳入摘要且不再发送");
        }
        return text.toString();
    }

    /**
     * 解析会话当前模型。
     * <p>
     * 三级回落（会话显式 → agent 偏好 → 全局默认）与对话路径共用 {@link SessionModelResolver}：
     * 压缩必须按同一个模型的窗口裁剪、花同一个模型的额度，两边各写一遍就一定会漂移。
     *
     * @param session 会话运行态
     * @return 解析结果
     * @throws JellyfishException 解析不到模型时抛出
     */
    private ResolvedModel resolveModel(Session session) {
        return sessionModelResolver.resolve(session);
    }

    /**
     * 解析会话当前模型，解析不出来时返回 {@code null}。
     * <p>
     * <b>为什么允许失败</b>：调用它只是为了让插件策略看到模型的窗口预算。而「这个会话有没有可压的历史」
     * 与模型无关——没配模型也该能回答 /compact preview。真到了非压不可的那一步，才用
     * {@link #requireModel} 让失败上浮。
     *
     * @param session 会话运行态
     * @return 解析结果；未配模型或解析失败时返回 {@code null}
     */
    private ResolvedModel resolveModelOrNull(Session session) {
        try {
            return resolveModel(session);
        } catch (RuntimeException e) {
            LOG.debug("压缩前的模型解析失败，按「未知窗口」继续: sessionId={} reason={}",
                    session.getSessionId(), messageOf(e));
            return null;
        }
    }

    /**
     * 取模型对应的客户端。
     *
     * @param resolvedModel 已解析的模型
     * @return LLM 客户端
     */
    private LlmClient clientOf(ResolvedModel resolvedModel) {
        return modelManager.getClient(resolvedModel);
    }

    /**
     * 取当前 ReAct 段配置。
     * <p>
     * 现读而不缓存：压缩的三项参数与上下文预留都在这一段里，热更新后下一次压缩立刻用新值。
     *
     * @return ReAct 段配置，保证非 {@code null}
     */
    private ReactSettings reactSettings() {
        return runtimeConfig.getReactSettings();
    }

    /**
     * 取异常的可用消息。
     *
     * @param throwable 异常
     * @return 消息文本；消息为空时退化为类名
     */
    private static String messageOf(Throwable throwable) {
        return StringUtils.isBlank(throwable.getMessage())
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    /**
     * 取插件给出的理由的可用文本。
     *
     * @param reason 理由，可为 {@code null}
     * @return 理由文本，空时返回固定占位
     */
    private static String reasonOf(String reason) {
        return StringUtils.isBlank(reason) ? "未提供理由" : reason;
    }

    /**
     * 创建专用守护线程池。
     *
     * @return 执行器
     */
    private static ExecutorService createExecutor() {
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(COMPACT_MAX_THREADS, COMPACT_MAX_THREADS,
                COMPACT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(COMPACT_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "compact");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        threadPool.allowCoreThreadTimeOut(true);
        return threadPool;
    }

    /**
     * 压缩状态机的状态。
     */
    public enum Status {

        /** 从未压缩过，或上一次的结果已经被外壳消化。 */
        IDLE,

        /** 正在压。 */
        RUNNING,

        /** 上次压缩成功。 */
        DONE,

        /** 上次压缩失败。 */
        FAILED
    }

    /**
     * 某会话的压缩状态快照：状态 + 一句给人看的说明。
     * <p>
     * <b>为什么把说明放在这里而不是让外壳自己拼</b>：外壳在轮询时只看到「状态变了」，
     * 它不知道压了多少条、摘要多长——那些数字只有执行者知道，而且执行完就没有第二个地方记得。
     * 让执行者一次性把话说完，外壳只负责显示。
     * <p>
     * 不可变，可安全跨线程传递。
     */
    public static final class State {

        /** 状态。 */
        private final Status status;

        /** 说明文本：{@code DONE} 是成功描述，{@code FAILED} 是失败原因，其余为空串。 */
        private final String message;

        /** 本次压缩的触发原因；{@code IDLE} 时为 {@code null}。 */
        private final CompactionTrigger trigger;

        /**
         * 构造状态快照。
         *
         * @param status  状态，不可为 {@code null}
         * @param message 说明文本，可为 {@code null}（等价空串）
         * @param trigger 触发原因，可为 {@code null}
         */
        private State(Status status, String message, CompactionTrigger trigger) {
            this.status = status;
            this.message = message == null ? "" : message;
            this.trigger = trigger;
        }

        /**
         * 构造「空闲」状态。
         * <p>
         * 外壳需要一个「还没有状态」的初值（首帧、没有会话时），否则每个调用点都要判空。
         *
         * @return 状态快照
         */
        public static State idle() {
            return new State(Status.IDLE, null, null);
        }

        /**
         * 构造「进行中」状态。
         *
         * @param trigger 触发原因，不可为 {@code null}
         * @return 状态快照
         */
        public static State running(CompactionTrigger trigger) {
            return new State(Status.RUNNING, null, trigger);
        }

        /**
         * 构造「成功」状态。
         *
         * @param message 成功描述
         * @param trigger 触发原因，不可为 {@code null}
         * @return 状态快照
         */
        public static State done(String message, CompactionTrigger trigger) {
            return new State(Status.DONE, message, trigger);
        }

        /**
         * 构造「失败」状态。
         * <p>
         * <b>失败也要带触发原因</b>：自动压缩失败时用户根本不知道发生过这件事，
         * 界面上那句提示是他唯一的机会。
         *
         * @param message 失败原因
         * @param trigger 触发原因，不可为 {@code null}
         * @return 状态快照
         */
        public static State failed(String message, CompactionTrigger trigger) {
            return new State(Status.FAILED, message, trigger);
        }

        /**
         * 获取状态。
         *
         * @return 状态
         */
        public Status getStatus() {
            return status;
        }

        /**
         * 获取说明文本。
         *
         * @return 说明文本，可能为空串但不会为 {@code null}
         */
        public String getMessage() {
            return message;
        }

        /**
         * 获取触发原因。
         *
         * @return 触发原因；{@link Status#IDLE} 时为 {@code null}
         */
        public CompactionTrigger getTrigger() {
            return trigger;
        }

        /**
         * 判断是否正在压。
         *
         * @return {@link Status#RUNNING} 返回 {@code true}
         */
        public boolean isRunning() {
            return status == Status.RUNNING;
        }

        @Override
        public String toString() {
            return "State{status=" + status + ", trigger=" + trigger + ", message=" + message + '}';
        }
    }
}
