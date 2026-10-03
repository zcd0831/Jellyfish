package zcd.jellyfish.core;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.TurnBeforeRequest;
import zcd.jellyfish.api.extension.TurnDirective;
import zcd.jellyfish.core.action.ActionDispatcher;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.prompt.PromptAssembly;
import zcd.jellyfish.core.prompt.ToolFilter;
import zcd.jellyfish.core.runtime.RunContext;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.llm.LlmStreamListener;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct 循环器：思考（调用模型）→ 行动（执行工具）→ 观察（把工具结果回灌），直至模型给出最终回复。
 * <p>
 * <b>为什么自带执行器</b>：每一轮循环线程都会阻塞等待 LLM 流结束，而流任务跑在 {@code llm-stream} 池里；
 * 若把循环也丢进同一个池，池满时会出现「循环线程等流线程、流线程排不上号」的自锁。因此这里自持一个小型
 * 守护线程池（线程名 {@code react}），不对外暴露、不复用别家的池。
 * <p>
 * <b>工具是同步扩展点</b>：{@code ExtensionRegistry} 在调用点线程内联执行，没有超时与异常隔离，
 * 因此本类在调用点自己兜底——权限拒绝、未知工具、工具抛错一律转成工具结果消息回灌给模型，让模型自适应，
 * 而不是把整条循环打断。只有「调用模型本身失败」才上抛。
 * <p>
 * <b>两种回合形态共用同一个循环</b>：{@link #chat} 是异步的顶层回合（跑在自持的 {@code react} 池上），
 * {@link #runNested} 是同步的嵌套回合（<b>在调用线程上内联跑完</b>，供子代理委派）。
 * 后者刻意不提交执行器：调用它的工具调用此刻正占着一条 {@code react} 线程，
 * 把嵌套回合再排回同一个池里，几条并发父回合就能把池占满并互相等死。
 * <p>
 * <b>取消</b>：{@link ReActTurn#cancel()} 置标志并掐断当前 LLM 流；循环在每轮开始与每个工具执行前检查标志。
 * 工具执行前被取消时，已落盘的 {@code assistant(toolCalls)} 缺的那些结果会一并补上——
 * 悬空的工具调用会让厂商以 400 拒掉之后的每一次请求。
 *
 * @author zcd
 */
@Singleton
public class ReActLooper implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ReActLooper.class);

    /** react 执行器线程数上限，即并发回合上限。 */
    private static final int REACT_MAX_THREADS = 8;

    /** react 执行器等待队列容量。 */
    private static final int REACT_QUEUE_CAPACITY = 128;

    /** react 线程空闲回收时间（秒）。 */
    private static final long REACT_KEEP_ALIVE_SECONDS = 60L;

    /** 达到最大轮次时回灌给调用方的提示模板；占位符是「下一步该调哪个配置键」。 */
    private static final String MAX_ROUNDS_MESSAGE = "已达到最大轮次仍未收敛，如需继续请调大 %s 或换一个更明确的指令。";

    /** 顶层回合的轮数上限配置键名。 */
    private static final String REACT_MAX_ROUNDS_KEY = "react.maxRounds";

    /** 嵌套回合（子代理）的轮数上限配置键名。 */
    private static final String SUBAGENT_MAX_ROUNDS_KEY = "subAgent.maxRounds";

    /** 终止原因取值：回合被取消时为未执行的工具调用补的结果标这个值（约定见 {@code ToolMetadata}）。 */
    private static final String TERMINAL_CANCELLED = "CANCELLED";

    /** 终止原因取值：回合因触达预算而截断时为未执行的工具调用补的结果标这个值。 */
    private static final String TERMINAL_TRUNCATED = "TRUNCATED";

    /** 回合被取消时，为未执行的工具调用补的合成结果正文。 */
    private static final String NOT_RUN_MESSAGE = "已取消：该工具调用未执行";

    /** 回合触达预算被截断时，为未执行的工具调用补的合成结果正文。 */
    private static final String TRUNCATED_NOT_RUN_MESSAGE = "已跳过：子代理触达预算，本次未执行";

    /** 会话域服务：读取会话状态、追加消息。 */
    private final SessionManager sessionManager;

    /** 模型门面：解析会话当前模型并给出客户端。 */
    private final ModelManager modelManager;

    /** 会话模型解析器：会话显式 → agent 偏好 → 全局默认。 */
    private final SessionModelResolver sessionModelResolver;

    /** 工具执行器：权限判定 → 路由 → 截断落盘，全仓库只此一处。 */
    private final ToolExecutor toolExecutor;

    /** 通知发布入口：工具埋点。 */
    private final EventPublisher events;

    /** 提示词与上下文组装器。 */
    private final PromptAssembler promptAssembler;

    /** 会话压缩器：上下文用满之前先压一次，免得机械裁剪把历史丢掉。 */
    private final ConversationCompactor conversationCompactor;

    /** 运行时配置门面：读取 ReAct 段。 */
    private final RuntimeConfig runtimeConfig;

    /** 委派作用域持有者：顶层回合开闭，嵌套回合进出。 */
    private final RunContextHolder runContexts;

    /** 同步扩展点策略：回合开始前的拦截从同一份注册表取。 */
    private final ExtensionRegistry extensions;

    /** 动作执行体：插件主动动作的排空与执行。 */
    private final ActionDispatcher actionDispatcher;

    /** 专用执行器。 */
    private final ExecutorService executor;

    /**
     * 构造 ReAct 循环器并创建专用执行器。
     *
     * @param sessionManager    会话域服务
     * @param modelManager      模型门面
     * @param toolExecutor      工具执行器
     * @param events            通知发布入口
     * @param promptAssembler   提示词组装器
     * @param runtimeConfig     运行时配置门面
     * @param conversationCompactor 会话压缩器
     * @param runContexts         委派作用域持有者
     * @param sessionModelResolver 会话模型解析器
     * @param extensions        同步扩展点策略
     * @param actionDispatcher  动作执行体
     */
    @Inject
    public ReActLooper(SessionManager sessionManager, ModelManager modelManager, ToolExecutor toolExecutor,
                       EventPublisher events, PromptAssembler promptAssembler, RuntimeConfig runtimeConfig,
                       ConversationCompactor conversationCompactor, RunContextHolder runContexts,
                       SessionModelResolver sessionModelResolver, ExtensionRegistry extensions,
                       ActionDispatcher actionDispatcher) {
        this(sessionManager, modelManager, toolExecutor, events, promptAssembler,
                runtimeConfig, conversationCompactor, runContexts, sessionModelResolver, extensions,
                actionDispatcher, createExecutor());
    }

    /**
     * 测试用构造器：注入执行器以便控制回合线程。
     *
     * @param sessionManager    会话域服务
     * @param modelManager      模型门面
     * @param toolExecutor      工具执行器
     * @param events            通知发布入口
     * @param promptAssembler   提示词组装器
     * @param runtimeConfig     运行时配置门面
     * @param conversationCompactor 会话压缩器
     * @param runContexts         委派作用域持有者
     * @param sessionModelResolver 会话模型解析器
     * @param extensions        同步扩展点策略
     * @param actionDispatcher  动作执行体
     * @param executor          专用执行器
     */
    ReActLooper(SessionManager sessionManager, ModelManager modelManager, ToolExecutor toolExecutor,
                EventPublisher events, PromptAssembler promptAssembler,
                RuntimeConfig runtimeConfig, ConversationCompactor conversationCompactor,
                RunContextHolder runContexts, SessionModelResolver sessionModelResolver, ExtensionRegistry extensions,
                ActionDispatcher actionDispatcher, ExecutorService executor) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.conversationCompactor = Objects.requireNonNull(conversationCompactor,
                "conversationCompactor must not be null");
        this.runContexts = Objects.requireNonNull(runContexts, "runContexts must not be null");
        this.sessionModelResolver = Objects.requireNonNull(sessionModelResolver,
                "sessionModelResolver must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.actionDispatcher = Objects.requireNonNull(actionDispatcher, "actionDispatcher must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * 启动一次 ReAct 回合，立即返回句柄，回合在 {@code react} 线程异步推进。
     *
     * @param sessionId 会话标识，不可为空白
     * @param userInput 用户输入，可为 {@code null}
     * @param listener  流式回调，可为 {@code null}（等价于 {@link ReActListener#NOOP}）
     * @return 回合句柄，保证非 {@code null}
     */
    public ReActTurn chat(String sessionId, String userInput, ReActListener listener) {
        return chat(sessionId, UUID.randomUUID().toString(), userInput, listener);
    }

    /**
     * 启动一次 ReAct 回合，使用外部给定的回合标识。
     * <p>
     * <b>标识为什么要由外部给</b>：外壳事件流上的 {@code turnId} 必须在回合启动之前就确定，
     * 否则订阅者收到的第一批事件会带不上它（而它们恰恰是最早的增量）。
     * 调用方是 {@code ConversationService}：它先生成标识、建好发布器，再起回合。
     *
     * @param sessionId 会话标识，不可为空白
     * @param turnId    回合标识，不可为空白
     * @param userInput 用户输入，可为 {@code null}
     * @param listener  流式回调，可为 {@code null}（等价于 {@link ReActListener#NOOP}）
     * @return 回合句柄，保证非 {@code null}
     */
    public ReActTurn chat(String sessionId, String turnId, String userInput, ReActListener listener) {
        ReActListener effective = listener == null ? ReActListener.NOOP : listener;
        ReActTurnImpl turn = new ReActTurnImpl(turnId);
        // 先登记再起回合：与外壳的回合闸门同理。反过来的话，「起回合」与「插件第一次投递」之间的
        // 动作会白跑一趟，而那个窗口在真实使用里正好是「插件收到回合开始事件」那一刻
        actionDispatcher.beginTurn(sessionId);
        try {
            turn.submit(executor, () -> execute(turn, sessionId, userInput, effective));
        } catch (RuntimeException e) {
            // 执行器已关闭：任务不会跑，放在任务里的注销也就永远不会发生
            actionDispatcher.endTurn(sessionId);
            throw e;
        }
        return turn;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 顶层回合入口：开一个委派作用域，跑完必关。
     * <p>
     * <b>作用域归属顶层回合而不是单次委派</b>：同一个回合里模型可以连着委派好几次，
     * 也可能某个子代理再往下委派；「本回合已经派出多少」这笔账只能挂在回合上。
     *
     * @param turn      回合句柄
     * @param sessionId 会话标识
     * @param userInput 用户输入
     * @param listener  监听器
     * @return 回合结果
     */
    private ReActResult execute(ReActTurnImpl turn, String sessionId, String userInput, ReActListener listener) {
        SubAgentSettings subAgent = runtimeConfig.getSubAgentSettings();
        runContexts.open(subAgent.getMaxDepth(), subAgent.getMaxSpawnsPerTurn(),
                subAgent.getRunTokenBudget(), subAgent.getTreeTokenBudget());
        try {
            Session session;
            try {
                // 先解析会话：不存在的会话是调用方的问题，也要经 onError 告诉它（与其余失败同口径）
                session = sessionManager.require(sessionId);
            } catch (JellyfishException e) {
                listener.onError(e);
                throw e;
            }
            return runTurn(turn, session, userInput, listener,
                    runtimeConfig.getReactSettings().getMaxRounds(), ToolFilter.none(), false);
        } finally {
            // react 池线程会被复用：不关的话下一个回合会继承本回合的深度与计数
            runContexts.close();
            // 注销必须是回合的最后一步：排空点都在这之前，之后的投递已经没有窗口可进——
            // 它们会在队列里被标成「本回合内没能排空」
            actionDispatcher.endTurn(sessionId);
        }
    }

    /**
     * 启动一次嵌套回合，<b>在调用线程上同步跑完</b>并直接返回结果。
     * <p>
     * <b>为什么不提交执行器</b>：调用方（工具处理器）此刻正阻塞在一条 {@code react} 线程上等它返回，
     * 把任务再排回同一个池里，几条并发父回合就能把池占满并互相等死——只有 8 条线程，而队列里的
     * 嵌套回合永远不会被谁让出位置。内联执行顺带得到两个好处：不需要新线程，取消与调用栈天然串联。
     * <p>
     * <b>不在回合作用域内时直接拒绝</b>：嵌套回合不单独开作用域（那就是在绕过深度与预算），
     * 拿不到父作用域说明它不是从某个顶层回合里派生出来的——那属于编程错误。
     * <p>
     * <b>取消由父令牌接管</b>：父回合被取消时，本回合的 LLM 流同时在同一个信号里被搞断。
     *
     * @param session           子代理会话，不可为 {@code null}
     * @param prompt            给子代理的任务原文，可为 {@code null}
     * @param listener          流式回调，可为 {@code null}（等价于 {@link ReActListener#NOOP}）
     * @param cancellationToken 父回合的取消令牌，可为 {@code null}（不接受外部取消）
     * @param maxRounds  本回合最大轮数，由调用方给出（子代理的轮数上限与主会话不同）
     * @param toolFilter 工具清单过滤器，不可为 {@code null}
     * @return 回合结果，保证非 {@code null}
     * @throws JellyfishException 不在顶层回合作用域内时抛出
     */
    public ReActResult runNested(Session session, String prompt, ReActListener listener,
                                 CancellationToken cancellationToken, int maxRounds, ToolFilter toolFilter) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(toolFilter, "toolFilter must not be null");
        RunContext scope = runContexts.current();
        if (scope == null) {
            throw new JellyfishException("nested turn requires an active run scope");
        }
        ReActListener effective = listener == null ? ReActListener.NOOP : listener;
        ReActTurnImpl turn = ReActTurnImpl.inline(cancellationToken);
        scope.enter();
        try {
            return runTurn(turn, session, prompt, effective, maxRounds, toolFilter, true);
        } finally {
            scope.leave();
        }
    }

    /**
     * 回合主体：追加输入消息后进入循环，异常统一收敛成 {@link ReActListener#onError}。
     * <p>
     * <b>回合的首尾就是延迟落盘的开头与结尾</b>：{@code beginTurn} 之后，回合内的消息追加只标脏，
     * {@code finally} 里的 {@code flush} 把整个回合一次性落盘。放在 {@code finally} 是为了盖住全部
     * 四条终结路径——正常收敛、取消、达到最大轮次、异常；放在这里而不是外壳，是因为外壳有三份
     * （CLI / TUI / Server），而回合只有这一处。
     * <p>
     * 顶层与嵌套共用它：两者的差别只有「回合从哪里来、轮数从哪读」，落盘与异常收敛同口径。
     *
     * @param turn      回合句柄
     * @param session   会话运行态
     * @param userInput 输入消息
     * @param listener  监听器
     * @param maxRounds  最大循环轮数
     * @param toolFilter 工具清单过滤器
     * @param nested     是否嵌套回合（子代理）：既传给回合上下文扩展点，也决定截断提示指向哪个配置键
     * @return 回合结果
     */
    private ReActResult runTurn(ReActTurnImpl turn, Session session, String userInput, ReActListener listener,
                                int maxRounds, ToolFilter toolFilter, boolean nested) {
        String sessionId = session.getSessionId();
        sessionManager.beginTurn(sessionId);
        try {
            // 回合开始前钩子：位置必须在追加用户消息之前——一旦消息进了会话，拦下就只剩「再删掉」
            // 这条路，而历史是 append-only 的（缓存前缀与落盘都依赖这条性质）。
            // 顶层与嵌套共用本方法，因此子代理路径不会绕过钩子
            TurnDirective directive = turnDirective(session, userInput, nested);
            if (directive.isCancelled()) {
                LOG.info("回合被插件拦下: sessionId={} reason={}", sessionId, directive.getReason());
                listener.onBlocked(directive.getReason());
                return ReActResult.blocked(sessionId, directive.getReason());
            }
            sessionManager.appendMessage(sessionId, LlmMessage.user(
                    withTurnContext(session, effectiveInput(directive, userInput, nested), nested)), null);
            return loop(turn, session, listener, maxRounds, toolFilter, nested);
        } catch (JellyfishException e) {
            listener.onError(e);
            throw e;
        } catch (RuntimeException e) {
            JellyfishException wrapped = new JellyfishException("react turn failed: " + e.getMessage(), e);
            listener.onError(wrapped);
            throw wrapped;
        } finally {
            sessionManager.flush(sessionId);
        }
    }

    /**
     * 循环主体：每轮一次模型调用，带工具调用则执行后继续，否则收敛。
     *
     * @param turn       回合句柄
     * @param session    会话运行态
     * @param listener   监听器
     * @param maxRounds  最大循环轮数，由调用方给出
     * @param toolFilter 工具清单过滤器
     * @param nested     是否嵌套回合（子代理），决定截断提示指向哪个配置键
     * @return 回合结果
     */
    private ReActResult loop(ReActTurnImpl turn, Session session, ReActListener listener, int maxRounds,
                             ToolFilter toolFilter, boolean nested) {
        String sessionId = session.getSessionId();
        for (int round = 1; round <= maxRounds; round++) {
            if (turn.isCancelled()) {
                return cancel(sessionId, listener, round - 1);
            }
            ResolvedModel resolvedModel = sessionModelResolver.resolve(session);
            PromptAssembly assembly = promptAssembler.assemble(session, resolvedModel, toolFilter);
            // 压缩与这一轮的模型调用并发：请求已经组装好了，它不会拖慢这一轮；
            // 结果在下一轮组装时才生效（那时边界已经推进）
            conversationCompactor.autoCompactIfNeeded(sessionId, assembly.getUsage());
            LlmResponse response = callStreaming(turn, modelManager.getClient(resolvedModel),
                    assembly.getRequest(), listener, sessionId);
            if (response == null) {
                return cancel(sessionId, listener, round - 1);
            }
            List<LlmToolCall> toolCalls = normalizeToolCalls(response.getToolCalls());
            sessionManager.appendMessage(sessionId, assistantMessage(response, toolCalls), response.getUsage(),
                    response.getThinking());
            String budgetExceeded = budgetExceededReason(session, response);
            if (budgetExceeded != null) {
                // 工具调用已随上面的 assistant 消息落库，必须补齐结果：悬空的 assistant(toolCalls)
                // 会让厂商以 400 拒掉之后的每一次请求（见 ToolPairing），而失败会一直重复
                LOG.warn("子代理触达预算，回合提前收敛: sessionId={} reason={}", sessionId, budgetExceeded);
                appendNotRunResults(sessionId, toolCalls, 0, TERMINAL_TRUNCATED);
                ReActResult limited = ReActResult.truncated(sessionId, budgetExceeded, round);
                listener.onComplete(limited);
                return limited;
            }
            if (toolCalls.isEmpty()) {
                // 收敛之前先把回合边界上的动作排掉（压缩 / 切换模型 / 插入点为「工具批次之后」的消息），
                // 再看有没有人要求「接着干」——那会插一条消息，让本回合多跑一轮，而不是开一个新回合
                actionDispatcher.drainTurnBoundary(sessionId, round < maxRounds);
                if (actionDispatcher.drainConvergence(sessionId, round < maxRounds)) {
                    continue;
                }
                ReActResult result = ReActResult.completed(sessionId, response.getContent(), round);
                listener.onComplete(result);
                return result;
            }
            // 用下标循环而不是 for-each：回合被取消时要能说出「哪些工具调用还没执行」，
            // 才能给它们补上结果（见 appendNotRunResults）
            for (int index = 0; index < toolCalls.size(); index++) {
                if (turn.isCancelled()) {
                    appendNotRunResults(sessionId, toolCalls, index);
                    return cancel(sessionId, listener, round);
                }
                LlmToolCall toolCall = toolCalls.get(index);
                ToolCallResult outcome = executeTool(turn, session, toolCall, listener);
                // 元数据随工具结果消息落会话：会话是「界面看到什么」的真源，而界面后的重投影
                // （以及 -resume 之后的历史）只有拿到字段才能渲染警告标记
                sessionManager.appendMessage(sessionId,
                        LlmMessage.tool(toolCall.getId(), toolCall.getName(), outputOf(outcome)),
                        null, null, outcome.getMetadata());
            }
            // 工具批次之后、下一次模型调用之前：STEER 的插入点。放这里而不是轮首，
            // 是因为「刚跑完一批工具」正是插件最可能知道「还有一步没做」的时刻
            actionDispatcher.drainTurnBoundary(sessionId, round < maxRounds);
        }
        LOG.warn("ReAct 达到最大轮次: sessionId={} maxRounds={}", sessionId, maxRounds);
        ReActResult result = ReActResult.truncated(sessionId, maxRoundsMessage(nested), maxRounds);
        listener.onComplete(result);
        return result;
    }

    /**
     * 组装「达到最大轮次」的提示文本。
     * <p>
     * <b>为什么按语境指向不同的配置键</b>：轮数上限由调用方给出——顶层回合读 {@code react.maxRounds}，
     * 嵌套回合（子代理）读 {@code subAgent.maxRounds}。共用一句写死的提示会让子代理那条路上指向一个
     * 调了也没用的键，而这条提示的全部价值就在于告诉调用方「下一步该改哪里」。
     *
     * @param nested 是否嵌套回合（子代理）
     * @return 提示文本，保证非 {@code null}
     */
    private static String maxRoundsMessage(boolean nested) {
        return String.format(Locale.ROOT, MAX_ROUNDS_MESSAGE,
                nested ? SUBAGENT_MAX_ROUNDS_KEY : REACT_MAX_ROUNDS_KEY);
    }

    /**
     * 发起一次流式调用并阻塞等待其结束。
     * <p>
     * 文本与思考增量实时转发；工具调用不在这里处理，只取 {@code onComplete} 里的聚合结果。
     *
     * @param turn    回合句柄，用于绑定 / 取消流句柄
     * @param client  LLM 客户端
     * @param request 请求
     * @param listener 监听器
     * @param sessionId 会话标识，失败时用于上报
     * @return 聚合后的响应；被取消时返回 {@code null}
     * @throws JellyfishException 流失败或等待被中断时抛出
     */
    private LlmResponse callStreaming(ReActTurnImpl turn, LlmClient client, LlmRequest request,
                                      ReActListener listener, String sessionId) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<LlmResponse> responseRef = new AtomicReference<LlmResponse>();
        AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();
        AtomicBoolean cancelledRef = new AtomicBoolean(false);
        LlmStreamListener streamListener = new LlmStreamListener() {
            @Override
            public void onText(String delta) {
                listener.onText(delta);
            }

            @Override
            public void onThinking(String delta) {
                listener.onThinking(delta);
            }

            @Override
            public void onComplete(LlmResponse response) {
                responseRef.set(response);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errorRef.set(error);
                latch.countDown();
            }

            @Override
            public void onCancelled() {
                cancelledRef.set(true);
                latch.countDown();
            }
        };
        LlmStreamHandle handle = client.chatStream(request, streamListener);
        turn.bindHandle(handle);
        if (turn.isCancelled()) {
            handle.cancel();
        }
        awaitStream(latch);
        if (cancelledRef.get() || turn.isCancelled()) {
            return null;
        }
        Throwable error = errorRef.get();
        if (error != null) {
            // 在这里上报、而不是等异常穿出去：状态码只长在原始异常的类型上，
            // 而下面那句包装会把它降成普通 JellyfishException。订阅方要靠状态码区分
            // 「字段被拒该降级」与「限流该重试」，丢了它这个事件就只剩一半用处
            sessionManager.publishCallFailure(sessionId, request.getModel(),
                    error instanceof JellyfishException ? (JellyfishException) error
                            : new JellyfishException(error.getMessage(), error));
            throw new JellyfishException("llm stream failed: " + error.getMessage(), error);
        }
        LlmResponse response = responseRef.get();
        if (response == null) {
            throw new JellyfishException("llm stream completed without response");
        }
        return response;
    }

    /**
     * 阻塞等待流结束。
     *
     * @param latch 计数闩锁
     * @throws JellyfishException 等待被中断时抛出
     */
    private static void awaitStream(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JellyfishException("interrupted while waiting for llm stream", e);
        }
    }

    /**
     * 执行一次工具调用，任何失败都转成可回灌的结果文本。
     * <p>
     * 真正的执行语义已搬到 {@link ToolExecutor}（输入指令那条路径也要用同一套权限与截断），
     * 这里只把「回合句柄即取消令牌」这件事接上去。
     *
     * @param turn     回合句柄，兼作取消令牌
     * @param session  会话运行态
     * @param toolCall 工具调用
     * @param listener 监听器
     * @return 工具结果（{@code output} 为回灌文本，{@code metadata} 为结构化元数据），保证非 {@code null}
     */
    private ToolCallResult executeTool(ReActTurnImpl turn, Session session, LlmToolCall toolCall,
                                       ReActListener listener) {
        return toolExecutor.execute(session, turn, toolCall.getId(), toolCall.getName(),
                toolCall.getArguments(), listener);
    }

    /**
     * 给「本轮被中断、结果从未落盘」的工具调用补发合成结果。
     * <p>
     * <b>为什么必须补</b>：{@code assistant(toolCalls)} 是在执行工具<b>之前</b>落盘的，因此一旦在
     * 工具执行前就被取消，会话里就留下一个悬空的工具调用。下一条请求会带着它发出去，而厂商会以 400
     * 拒掉<b>整次请求</b>——该会话在边界下一次推进之前每一次请求都会失败，现场表现是「换模型、改配置
     * 都救不回来」。约束的完整说明见 {@code ToolPairing}。
     * <p>
     * <b>为什么取消是唯一需要处理的情形</b>：{@code ToolExecutor} 把「参数解析失败」与「工具抛错」
     * 一律转成失败结果而不上抛（见 {@code ToolExecutor.execute} 的两处 catch），因此循环不会因为
     * 工具失败而提前退出。落盘本身失败这类兜底之外的情形，由 {@code PromptAssembler} 在出站前再兜一层。
     * <p>
     * <b>正文写一句给人看的话，元数据标 CANCELLED</b>：遵守 {@code ToolMetadata.KEY_TERMINAL}
     * 「缺省 = 正常跑完」的约定——不标的话界面会把一条根本没跑过的工具渲染成正常完成。
     * 取值用既有的 {@code CANCELLED}（与 {@code REJECTED} / {@code TIMEOUT} / {@code FAILED} 同一套词汇），
     * 不自创词。
     *
     * @param sessionId 会话标识
     * @param toolCalls 本轮模型的全部工具调用，不可为 {@code null}
     * @param fromIndex 从这个下标起（含）的工具调用没有执行过
     */
    private void appendNotRunResults(String sessionId, List<LlmToolCall> toolCalls, int fromIndex) {
        appendNotRunResults(sessionId, toolCalls, fromIndex, TERMINAL_CANCELLED);
    }

    /**
     * 给「本轮未执行」的工具调用补发合成结果，并指定终止原因。
     *
     * @param sessionId 会话标识
     * @param toolCalls 本批工具调用
     * @param fromIndex 从哪个下标开始补
     * @param terminal  终止原因取值
     */
    private void appendNotRunResults(String sessionId, List<LlmToolCall> toolCalls, int fromIndex,
                                     String terminal) {
        Map<String, Object> metadata = Collections.singletonMap(ToolMetadata.KEY_TERMINAL, terminal);
        String text = TERMINAL_TRUNCATED.equals(terminal) ? TRUNCATED_NOT_RUN_MESSAGE : NOT_RUN_MESSAGE;
        for (int index = fromIndex; index < toolCalls.size(); index++) {
            LlmToolCall toolCall = toolCalls.get(index);
            sessionManager.appendMessage(sessionId,
                    LlmMessage.tool(toolCall.getId(), toolCall.getName(), text),
                    null, null, metadata);
        }
    }

    /**
     * 判断当前 run 是否触达了预算，返回触达原因或 {@code null}。
     * <p>
     * <b>只约束子代理 run，不约束顶层回合</b>：顶层回合的额度是用户自己的对话，不由 governor 管；
     * 判据是当前上下文有没有 runId（顶层回合的根上下文为 {@code null}）。
     * <p>
     * <b>先累加树账再比对</b>：树账是所有 run 的增量之和，并行时多个 run 会并发累加，
     * 因此累加发生在共享的 {@code RunTree} 上（原子）。单 run 的账直接取会话累计用量。
     *
     * @param session  当前会话
     * @param response 本轮模型响应
     * @return 触达原因；未触达或不是子代理 run 时返回 {@code null}
     */
    private String budgetExceededReason(Session session, LlmResponse response) {
        RunContext context = runContexts.current();
        if (context == null || context.getRunId() == null) {
            return null;
        }
        long roundTokens = response.getUsage() == null ? 0L : response.getUsage().getTotalTokens();
        long treeTokens = context.recordTreeTokens(roundTokens);
        long runTokens = session.getUsage().getTotalTokens();
        if (context.getRunTokenBudget() > 0L && runTokens > context.getRunTokenBudget()) {
            return "已达到单个子代理的 token 预算（" + context.getRunTokenBudget()
                    + "）：如需继续请调大 subAgent.runTokenBudget，或换更聚焦的任务。";
        }
        if (context.getTreeTokenBudget() > 0L && treeTokens > context.getTreeTokenBudget()) {
            return "这棵委派树的 token 预算已用尽（" + context.getTreeTokenBudget()
                    + "）：如需继续请调大 subAgent.treeTokenBudget。";
        }
        return null;
    }

    /**
     * 取工具结果的回灌文本。
     * <p>
     * {@code executeTool} 返回的是统一的 {@link ToolCallResult}（文本 + 元数据），而写会话时只需要文本。
     * {@code output} 在这里恒为 {@code String}（由 {@code ToolOutputLimiter} 渲染），但仍按
     * {@code Object} 声明——工具结果本来就可以是结构化对象，类型由载体决定。
     *
     * @param outcome 工具结果，不可为 {@code null}
     * @return 回灌文本，可为 {@code null}
     */
    private static String outputOf(ToolCallResult outcome) {
        Object output = outcome.getOutput();
        return output == null ? null : output.toString();
    }

    /**
     * 归一化工具调用：补齐缺失的调用 id，保证工具结果能与之配对。
     *
     * @param toolCalls 模型返回的工具调用
     * @return 归一化后的列表，可能为空但不会为 {@code null}
     */
    private static List<LlmToolCall> normalizeToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        List<LlmToolCall> normalized = new ArrayList<LlmToolCall>(toolCalls.size());
        for (LlmToolCall toolCall : toolCalls) {
            if (StringUtils.isBlank(toolCall.getId())) {
                normalized.add(new LlmToolCall(toolCall.getIndex(), UUID.randomUUID().toString(),
                        toolCall.getName(), toolCall.getArguments()));
            } else {
                normalized.add(toolCall);
            }
        }
        return normalized;
    }

    /**
     * 构造 assistant 消息：无工具调用时走纯文本版本。
     *
     * @param response  模型响应
     * @param toolCalls 归一化后的工具调用
     * @return assistant 消息
     */
    private static LlmMessage assistantMessage(LlmResponse response, List<LlmToolCall> toolCalls) {
        if (toolCalls.isEmpty()) {
            return LlmMessage.assistant(response.getContent());
        }
        return LlmMessage.assistant(response.getContent(), toolCalls);
    }

    /**
     * 跑一遍回合开始前钩子链，返回最终指令。
     * <p>
     * <b>链式语义</b>（写在调用点的 {@code for} 循环里，注册表不参与）：第一个 {@code cancel}
     * 立即短路（理由取自它）；{@code replaceInput} 取<b>最后一个非缺省</b>值。
     * <p>
     * <b>失败语义是「放行」</b>：同步派发没有护栏，异常处置是本方法的责任；拦截点坏掉时宁可放行，
     * 也不要让整个会话彻底不能用。
     * <p>
     * <b>无插件时不构造任何请求对象</b>，行为与引入本钩子之前逐字段一致。
     *
     * @param session   会话运行态
     * @param userInput 用户输入原文
     * @param nested    是否嵌套回合
     * @return 最终指令；链上没有可用处理器或都没意见时为 {@link TurnDirective#proceed()}
     */
    private TurnDirective turnDirective(Session session, String userInput, boolean nested) {
        List<ExtensionHandler<TurnBeforeRequest, TurnDirective>> handlers =
                extensions.handlers(TurnBeforeRequest.class, null);
        if (handlers.isEmpty()) {
            return TurnDirective.proceed();
        }
        RunContext scope = runContexts.current();
        int depth = scope == null ? 0 : scope.getDepth();
        String sessionId = session.getSessionId();
        String agentId = session.getAgentId();
        String input = userInput;
        boolean replaced = false;
        for (ExtensionHandler<TurnBeforeRequest, TurnDirective> handler : handlers) {
            TurnDirective directive;
            try {
                directive = extensions.invoke(handler,
                        new TurnBeforeRequest(sessionId, agentId, input, nested, depth));
            } catch (RuntimeException e) {
                LOG.warn("回合开始前处理器抛错，按放行处理: sessionId={}", sessionId, e);
                continue;
            }
            if (directive == null) {
                continue;
            }
            if (directive.isCancelled()) {
                return directive;
            }
            if (directive.hasInput()) {
                input = directive.getInput();
                replaced = true;
            }
        }
        return replaced ? TurnDirective.replaceInput(input) : TurnDirective.proceed();
    }

    /**
     * 取本次回合实际要追加的输入。
     * <p>
     * <b>嵌套回合忽略 {@code replaceInput}</b>：它的输入是模型写出来的任务描述，改写会让
     * 「模型要什么」与「子代理收到什么」分叉，而模型无从得知。请求里带了 {@code nested}，
     * 插件本可以自己判断；这里再兜一次底，保证任何插件都改不动模型给出的任务描述。
     *
     * @param directive 钩子给出的指令
     * @param userInput 用户输入原文
     * @param nested    是否嵌套回合
     * @return 实际要追加的输入
     */
    private static String effectiveInput(TurnDirective directive, String userInput, boolean nested) {
        if (!directive.hasInput()) {
            return userInput;
        }
        if (nested) {
            LOG.debug("嵌套回合忽略 replaceInput：子代理的任务描述由模型给出，插件改不动它");
            return userInput;
        }
        return directive.getInput();
    }

    /**
     * 把各插件本轮要送的即时状态拼到用户输入前面。
     * <p>
     * <b>为什么拼进本轮用户消息，而不是放进 system prompt 或插一条消息</b>：缓存是前缀匹配，
     * system prompt 是第 0 个 token——待办进度这类「说变就变」的状态放进去，一次变化就要作废
     * 整个请求；而插一条独立消息会让会话账本多出一条谁也没说过的话，屏幕投影与 {@code /resume}
     * 跟着一起失真。拼进本轮用户消息是唯一两全的位置：它是 append-only 的，只影响本轮新产生的 token。
     * <p>
     * <b>没有上下文时原样返回</b>：绝大多数会话（没有这类插件）不该因为引入了这个扩展点而让
     * 用户消息多出一个换行。
     *
     * @param session   会话运行态
     * @param userInput 用户输入原文
     * @param nested    是否嵌套回合
     * @return 拼好上下文之后的输入；无人应答时原样返回
     */
    private String withTurnContext(Session session, String userInput, boolean nested) {
        String context = promptAssembler.turnContextOf(session.getSessionId(), userInput, nested);
        if (context == null) {
            return userInput;
        }
        return context + "\n\n" + userInput;
    }

    /**
     * 收敛取消回合。
     *
     * @param sessionId 会话标识
     * @param listener  监听器
     * @param rounds    已完成的轮数
     * @return 取消结果
     */
    private static ReActResult cancel(String sessionId, ReActListener listener, int rounds) {
        listener.onCancelled();
        return ReActResult.cancelled(sessionId, rounds);
    }

    /**
     * 创建专用守护线程池。
     *
     * @return 执行器
     */
    private static ExecutorService createExecutor() {
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(REACT_MAX_THREADS, REACT_MAX_THREADS,
                REACT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(REACT_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "react");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        threadPool.allowCoreThreadTimeOut(true);
        return threadPool;
    }
}
