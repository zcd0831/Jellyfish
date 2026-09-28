package zcd.jellyfish.core;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.core.prompt.PromptAssembly;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.llm.LlmStreamListener;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <b>取消</b>：{@link ReActTurn#cancel()} 置标志并掐断当前 LLM 流；循环在每轮开始与每个工具执行前检查标志。
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

    /** 达到最大轮次时回灌给调用方的提示。 */
    private static final String MAX_ROUNDS_MESSAGE = "已达到最大轮次仍未收敛，如需继续请调大 react.maxRounds 或换一个更明确的指令。";

    /** 会话域服务：读取会话状态、追加消息。 */
    private final SessionManager sessionManager;

    /** 模型门面：解析会话当前模型并给出客户端。 */
    private final ModelManager modelManager;

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
     */
    @Inject
    public ReActLooper(SessionManager sessionManager, ModelManager modelManager, ToolExecutor toolExecutor,
                       EventPublisher events, PromptAssembler promptAssembler, RuntimeConfig runtimeConfig,
                       ConversationCompactor conversationCompactor) {
        this(sessionManager, modelManager, toolExecutor, events, promptAssembler,
                runtimeConfig, conversationCompactor, createExecutor());
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
     * @param executor          专用执行器
     */
    ReActLooper(SessionManager sessionManager, ModelManager modelManager, ToolExecutor toolExecutor,
                EventPublisher events, PromptAssembler promptAssembler,
                RuntimeConfig runtimeConfig, ConversationCompactor conversationCompactor,
                ExecutorService executor) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.conversationCompactor = Objects.requireNonNull(conversationCompactor,
                "conversationCompactor must not be null");
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
        ReActListener effective = listener == null ? ReActListener.NOOP : listener;
        ReActTurnImpl turn = new ReActTurnImpl();
        turn.submit(executor, () -> execute(turn, sessionId, userInput, effective));
        return turn;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 回合入口：追加用户消息后进入循环，异常统一收敛成 {@link ReActListener#onError}。
     * <p>
     * <b>回合的首尾就是延迟落盘的开头与结尾</b>：{@code beginTurn} 之后，回合内的消息追加只标脏，
     * {@code finally} 里的 {@code flush} 把整个回合一次性落盘。放在 {@code finally} 是为了盖住全部
     * 四条终结路径——正常收敛、取消、达到最大轮次、异常；放在这里而不是外壳，是因为外壳有三份
     * （CLI / TUI / Server），而回合只有这一处。
     *
     * @param turn      回合句柄
     * @param sessionId 会话标识
     * @param userInput 用户输入
     * @param listener  监听器
     * @return 回合结果
     */
    private ReActResult execute(ReActTurnImpl turn, String sessionId, String userInput, ReActListener listener) {
        sessionManager.beginTurn(sessionId);
        try {
            Session session = sessionManager.require(sessionId);
            sessionManager.appendMessage(sessionId, LlmMessage.user(userInput), null);
            return loop(turn, session, listener);
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
     * @param turn     回合句柄
     * @param session  会话运行态
     * @param listener 监听器
     * @return 回合结果
     */
    private ReActResult loop(ReActTurnImpl turn, Session session, ReActListener listener) {
        String sessionId = session.getSessionId();
        int maxRounds = runtimeConfig.getReactSettings().getMaxRounds();
        for (int round = 1; round <= maxRounds; round++) {
            if (turn.isCancelled()) {
                return cancel(sessionId, listener, round - 1);
            }
            ResolvedModel resolvedModel = resolveModel(session);
            PromptAssembly assembly = promptAssembler.assemble(session, resolvedModel);
            // 压缩与这一轮的模型调用并发：请求已经组装好了，它不会拖慢这一轮；
            // 结果在下一轮组装时才生效（那时边界已经推进）
            conversationCompactor.autoCompactIfNeeded(sessionId, assembly.getUsage());
            LlmResponse response = callStreaming(turn, modelManager.getClient(resolvedModel),
                    assembly.getRequest(), listener);
            if (response == null) {
                return cancel(sessionId, listener, round - 1);
            }
            List<LlmToolCall> toolCalls = normalizeToolCalls(response.getToolCalls());
            sessionManager.appendMessage(sessionId, assistantMessage(response, toolCalls), response.getUsage(),
                    response.getThinking());
            if (toolCalls.isEmpty()) {
                ReActResult result = ReActResult.completed(sessionId, response.getContent(), round);
                listener.onComplete(result);
                return result;
            }
            for (LlmToolCall toolCall : toolCalls) {
                if (turn.isCancelled()) {
                    return cancel(sessionId, listener, round);
                }
                ToolCallResult outcome = executeTool(turn, session, toolCall, listener);
                // 元数据随工具结果消息落会话：会话是「界面看到什么」的真源，而界面后的重投影
                // （以及 -resume 之后的历史）只有拿到字段才能渲染警告标记
                sessionManager.appendMessage(sessionId,
                        LlmMessage.tool(toolCall.getId(), toolCall.getName(), outputOf(outcome)),
                        null, null, outcome.getMetadata());
            }
        }
        LOG.warn("ReAct 达到最大轮次: sessionId={} maxRounds={}", sessionId, maxRounds);
        ReActResult result = ReActResult.truncated(sessionId, MAX_ROUNDS_MESSAGE, maxRounds);
        listener.onComplete(result);
        return result;
    }

    /**
     * 解析会话当前模型：显式配置了 provider 与 model 就精确解析，否则跟随默认。
     *
     * @param session 会话运行态
     * @return 解析结果
     * @throws JellyfishException 解析不到模型时抛出
     */
    private ResolvedModel resolveModel(Session session) {
        String provider = session.getProvider();
        String model = session.getModel();
        if (StringUtils.isAnyBlank(provider, model)) {
            return modelManager.resolveDefault();
        }
        return modelManager.resolve(provider, model);
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
     * @return 聚合后的响应；被取消时返回 {@code null}
     * @throws JellyfishException 流失败或等待被中断时抛出
     */
    private LlmResponse callStreaming(ReActTurnImpl turn, LlmClient client, LlmRequest request,
                                      ReActListener listener) {
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
