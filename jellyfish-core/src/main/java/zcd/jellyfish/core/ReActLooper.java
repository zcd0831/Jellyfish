package zcd.jellyfish.core;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.core.prompt.PromptAssembler;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
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
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** 工具输出被截断时追加的标记。 */
    private static final String OUTPUT_TRUNCATION_MARKER = "\n…（工具输出已截断）";

    /** 会话域服务：读取会话状态、追加消息。 */
    private final SessionManager sessionManager;

    /** 模型门面：解析会话当前模型并给出客户端。 */
    private final ModelManager modelManager;

    /** 权限管理器：工具执行前的同步判定。 */
    private final PermissionManager permissionManager;

    /** 同步扩展点策略：工具路由。 */
    private final ExtensionRegistry extensions;

    /** 通知发布入口：工具埋点。 */
    private final EventPublisher events;

    /** 提示词与上下文组装器。 */
    private final PromptAssembler promptAssembler;

    /** 运行时配置门面：读取 ReAct 段。 */
    private final RuntimeConfig runtimeConfig;

    /** 专用执行器。 */
    private final ExecutorService executor;

    /**
     * 构造 ReAct 循环器并创建专用执行器。
     *
     * @param sessionManager    会话域服务
     * @param modelManager      模型门面
     * @param permissionManager 权限管理器
     * @param extensions        同步扩展点策略
     * @param events            通知发布入口
     * @param promptAssembler   提示词组装器
     * @param runtimeConfig     运行时配置门面
     */
    @Inject
    public ReActLooper(SessionManager sessionManager, ModelManager modelManager,
                       PermissionManager permissionManager, ExtensionRegistry extensions,
                       EventPublisher events, PromptAssembler promptAssembler, RuntimeConfig runtimeConfig) {
        this(sessionManager, modelManager, permissionManager, extensions, events, promptAssembler,
                runtimeConfig, createExecutor());
    }

    /**
     * 测试用构造器：注入执行器以便控制回合线程。
     *
     * @param sessionManager    会话域服务
     * @param modelManager      模型门面
     * @param permissionManager 权限管理器
     * @param extensions        同步扩展点策略
     * @param events            通知发布入口
     * @param promptAssembler   提示词组装器
     * @param runtimeConfig     运行时配置门面
     * @param executor          专用执行器
     */
    ReActLooper(SessionManager sessionManager, ModelManager modelManager, PermissionManager permissionManager,
                ExtensionRegistry extensions, EventPublisher events, PromptAssembler promptAssembler,
                RuntimeConfig runtimeConfig, ExecutorService executor) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.promptAssembler = Objects.requireNonNull(promptAssembler, "promptAssembler must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
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
     *
     * @param turn      回合句柄
     * @param sessionId 会话标识
     * @param userInput 用户输入
     * @param listener  监听器
     * @return 回合结果
     */
    private ReActResult execute(ReActTurnImpl turn, String sessionId, String userInput, ReActListener listener) {
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
            LlmRequest request = promptAssembler.buildRequest(session, resolvedModel);
            LlmResponse response = callStreaming(turn, modelManager.getClient(resolvedModel), request, listener);
            if (response == null) {
                return cancel(sessionId, listener, round - 1);
            }
            List<LlmToolCall> toolCalls = normalizeToolCalls(response.getToolCalls());
            sessionManager.appendMessage(sessionId, assistantMessage(response, toolCalls), response.getUsage());
            if (toolCalls.isEmpty()) {
                ReActResult result = ReActResult.completed(sessionId, response.getContent(), round);
                listener.onComplete(result);
                return result;
            }
            for (LlmToolCall toolCall : toolCalls) {
                if (turn.isCancelled()) {
                    return cancel(sessionId, listener, round);
                }
                String output = executeTool(session, toolCall, listener);
                sessionManager.appendMessage(sessionId, LlmMessage.tool(toolCall.getId(), toolCall.getName(), output),
                        null);
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
     *
     * @param session  会话运行态
     * @param toolCall 工具调用
     * @param listener 监听器
     * @return 工具结果文本，保证非 {@code null}
     */
    private String executeTool(Session session, LlmToolCall toolCall, ReActListener listener) {
        String toolCallId = toolCall.getId();
        String toolName = toolCall.getName();
        events.publish(new ToolCallStartedEvent(toolCallId, toolName, session.getSessionId()));
        listener.onToolCallStarted(toolCallId, toolName);
        long start = System.currentTimeMillis();
        String output;
        boolean success = true;
        try {
            output = invokeTool(session, toolCall);
        } catch (RuntimeException e) {
            // 同步侧没有护栏，异常处置是调用点（这里）的责任：记失败、回灌、继续循环
            LOG.warn("工具执行失败: sessionId={} tool={}", session.getSessionId(), toolName, e);
            output = "工具执行失败：" + messageOf(e);
            success = false;
        }
        long duration = System.currentTimeMillis() - start;
        events.publish(new ToolCallCompletedEvent(toolCallId, toolName, success, duration,
                success ? null : output, session.getSessionId()));
        listener.onToolCallCompleted(toolCallId, toolName, success, output);
        return truncateOutput(output);
    }

    /**
     * 权限判定 + 路由 + 调用，返回工具输出文本。
     *
     * @param session  会话运行态
     * @param toolCall 工具调用
     * @return 工具输出文本，保证非 {@code null}
     */
    private String invokeTool(Session session, LlmToolCall toolCall) {
        String toolName = toolCall.getName();
        Map<String, Object> arguments = parseArguments(toolCall.getArguments());
        PermissionDecision decision = permissionManager.decide(new PermissionCheckRequest(session.getAgentId(),
                toolName, arguments, session.getPermissionMode(), session.getSessionId()));
        if (decision.isDenied()) {
            return "权限拒绝：" + messageOf(decision.getReason());
        }
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler;
        try {
            handler = extensions.handler(ToolCallRequest.class, toolName);
        } catch (ExtensionException e) {
            if (e.getCode() == ExtensionException.Code.NO_HANDLER) {
                return "未知工具：" + toolName;
            }
            return "工具注册冲突：" + toolName;
        }
        ToolCallResult result = extensions.invoke(handler,
                new ToolCallRequest(toolName, arguments, session.getSessionId()));
        return serializeOutput(result);
    }

    /**
     * 解析工具参数 JSON。
     *
     * @param json 参数 JSON 字符串，可为空
     * @return 参数字典，保证非 {@code null}
     * @throws JellyfishException JSON 非法时抛出
     */
    private static Map<String, Object> parseArguments(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        Map<String, Object> arguments;
        try {
            arguments = ObjectMapperWrapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException e) {
            throw new JellyfishException("工具参数解析失败: " + json, e);
        }
        return arguments == null ? Collections.<String, Object>emptyMap() : arguments;
    }

    /**
     * 把工具结果序列化成回灌文本。
     *
     * @param result 工具结果，可为 {@code null}
     * @return 文本，保证非 {@code null}
     */
    private static String serializeOutput(ToolCallResult result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        Object output = result.getOutput();
        if (output instanceof String) {
            return (String) output;
        }
        return ObjectMapperWrapper.writeValueAsString(output);
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
     * 按配置截断过长的工具输出。
     *
     * @param output 工具输出
     * @return 截断后的文本，保证非 {@code null}
     */
    private String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        int maxChars = runtimeConfig.getReactSettings().getMaxToolOutputChars();
        if (output.length() <= maxChars) {
            return output;
        }
        return output.substring(0, maxChars) + OUTPUT_TRUNCATION_MARKER;
    }

    /**
     * 取异常的可用消息。
     *
     * @param throwable 异常，可为 {@code null}
     * @return 消息文本；消息为空时退化为类名
     */
    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        return StringUtils.isBlank(throwable.getMessage())
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    /**
     * 取权限判定理由的可用文本。
     *
     * @param reason 理由，可为 {@code null}
     * @return 理由文本，空时返回固定占位
     */
    private static String messageOf(String reason) {
        return StringUtils.isBlank(reason) ? "未提供理由" : reason;
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
