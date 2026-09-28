package zcd.jellyfish.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行器：一次工具调用从「权限判定」到「回灌文本」的完整链路，只此一处。
 * <p>
 * <b>为什么把它从 {@code ReActLooper} 抽出来</b>：工具调用现在有两条发起方——模型（ReAct 循环）与用户
 * （输入指令，如 {@code !ls}）。执行语义里包含权限判定、审批、事件埋点、输出捕获与截断落盘、
 * 异常转结果文本，复制一份到第二条路径上就等于把这些口径维护两遍，漂移只是时间问题。
 * <p>
 * <b>调用方线程语义</b>：本类不做异步，在调用线程上同步执行完整个工具调用。因此 ReAct 循环在自己的
 * {@code react} 线程上调用，输入指令在自己的执行器线程上调用；两者都不允许在界面线程上直接调。
 * <p>
 * <b>权限是唯一入口</b>：任何发起方都必须经过 {@link #execute}——这也是「插件只能声明工具调用意图、
 * 不能自己执行」这条边界的落点：插件够不到 {@code PermissionManager}，而本类必然调用它。
 * <p>
 * <b>异常不逃逸</b>：工具抛错、权限拒绝、未知工具一律转成 {@link ToolCallResult} 的文本，
 * 让调用方（模型或上下文）能看见失败原因并自适应；只有编程错误（例如参数解析用了非法 JSON）才抛，
 * 但那同样会被本类接住并转成结果文本。
 *
 * @author zcd
 */
@Singleton
public class ToolExecutor {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    /** 权限管理器：工具执行前的同步判定。 */
    private final PermissionManager permissionManager;

    /** 同步扩展点策略：工具路由。 */
    private final ExtensionRegistry extensions;

    /** 通知发布入口：工具埋点。 */
    private final EventPublisher events;

    /** 工具输出中间件：回灌前的唯一硬截断点。 */
    private final ToolOutputLimiter outputLimiter;

    /**
     * 构造工具执行器。
     *
     * @param permissionManager 权限管理器，不可为 {@code null}
     * @param extensions        同步扩展点策略，不可为 {@code null}
     * @param events            通知发布入口，不可为 {@code null}
     * @param outputLimiter     工具输出中间件，不可为 {@code null}
     */
    @Inject
    public ToolExecutor(PermissionManager permissionManager, ExtensionRegistry extensions,
                        EventPublisher events, ToolOutputLimiter outputLimiter) {
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.outputLimiter = Objects.requireNonNull(outputLimiter, "outputLimiter must not be null");
    }

    /**
     * 执行一次工具调用，参数是模型给出的 JSON 文本。
     * <p>
     * 供 ReAct 循环使用：模型返回的工具调用参数是 JSON 串，这里解析后再走同一条执行路径。
     * 解析失败也转成结果文本（模型能看懂并改正），与工具自身抛错的处置一致。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param cancellation  取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param toolCallId    工具调用标识，不可为空白
     * @param toolName      工具名，不可为空白
     * @param argumentsJson 参数 JSON，可为空白（等价空参数）
     * @param listener      流式回调，可为 {@code null}（等价 {@link ReActListener#NOOP}）
     * @return 工具结果，保证非 {@code null}
     */
    public ToolCallResult execute(Session session, CancellationToken cancellation,
                                  String toolCallId, String toolName, String argumentsJson,
                                  ReActListener listener) {
        Objects.requireNonNull(session, "session must not be null");
        Map<String, Object> arguments;
        try {
            arguments = parseArguments(argumentsJson);
        } catch (RuntimeException e) {
            LOG.warn("工具参数解析失败: sessionId={} tool={}", session.getSessionId(), toolName, e);
            return new ToolCallResult(toolName, failureText(null, e));
        }
        return execute(session, cancellation, toolCallId, toolName, arguments, listener);
    }

    /**
     * 执行一次工具调用，参数已是映射。
     * <p>
     * 供输入指令使用：插件声明的是结构化参数，不需要再过一遍 JSON。
     *
     * @param session      会话运行态，不可为 {@code null}
     * @param cancellation 取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param toolCallId   工具调用标识，不可为空白
     * @param toolName     工具名，不可为空白
     * @param arguments    工具参数，可为 {@code null}（等价空参数）
     * @param listener     流式回调，可为 {@code null}（等价 {@link ReActListener#NOOP}）
     * @return 工具结果，保证非 {@code null}
     */
    public ToolCallResult execute(Session session, CancellationToken cancellation,
                                  String toolCallId, String toolName, Map<String, Object> arguments,
                                  ReActListener listener) {
        Objects.requireNonNull(session, "session must not be null");
        ReActListener effective = listener == null ? ReActListener.NOOP : listener;
        String sessionId = session.getSessionId();
        events.publish(new ToolCallStartedEvent(toolCallId, toolName, sessionId));
        effective.onToolCallStarted(toolCallId, toolName);
        long start = System.currentTimeMillis();
        // 捕获通道与取消令牌都随请求交给工具：无界输出的工具（命令行）靠前者不必物化整份输出，
        // 靠后者才能在用户按下 Esc 时被打断——同步派发不会中断正在执行的工具。
        // tee 把捕获到的片段同时转给外壳：它只是旁路（可丢、抛错被隔离），既不参与回灌也不落盘
        ToolOutputSink sink = outputLimiter.sink(sessionId, toolCallId, toolName,
                chunk -> effective.onToolCallOutput(toolCallId, toolName, chunk));
        ToolCallResult invoked;
        boolean success = true;
        try {
            invoked = invokeTool(session, cancellation, toolCallId, toolName, arguments, sink);
        } catch (RuntimeException e) {
            // 同步侧没有护栏，异常处置是调用点（这里）的责任：记失败、回灌、继续循环
            LOG.warn("工具执行失败: sessionId={} tool={}", sessionId, toolName, e);
            invoked = new ToolCallResult(toolName, failureText(sink, e));
            success = false;
        } finally {
            // 工具自己应当已经收尾；这里是兜底，保证落盘句柄一定释放、临时文件一定改名。
            // 幂等，因此正常路径上再调一次不会有副作用
            sink.finish();
        }
        if (invoked == null) {
            // 处理器返回 null 是允许的（api 里 output 可为 null），这里补一个空结果，
            // 让下面那条「文本 + 元数据」的统一处理不必到处判空
            invoked = new ToolCallResult(toolName, null);
        }
        // 截断与落盘只在这里做一次：回灌给模型、写入会话、通知外壳看到的必须是同一份文本，
        // 否则会出现「界面显示全文、模型收到信封」这种无法排查的不一致
        Object raw = invoked.getOutput();
        String output = outputLimiter.limit(sessionId, toolCallId, toolName, raw);
        long duration = System.currentTimeMillis() - start;
        events.publish(new ToolCallCompletedEvent(toolCallId, toolName, success, duration,
                success ? null : output, sessionId));
        // 元数据不受截断影响：它描述的是「命令成没成」，与回灌文本被截成什么样无关
        effective.onToolCallCompleted(toolCallId, toolName, success, output, invoked.getMetadata());
        return new ToolCallResult(toolName, output, invoked.getMetadata());
    }

    /**
     * 权限判定 + 路由 + 调用，返回工具输出对象。
     * <p>
     * <b>不在这里序列化</b>：截断必须知道「这是字符串还是结构化对象」才能选对算法，
     * 一旦在这里转成文本，那份信息就丢失了。
     *
     * @param session      会话运行态
     * @param cancellation 取消令牌，可为 {@code null}
     * @param toolCallId   工具调用标识
     * @param toolName     工具名
     * @param arguments    工具参数，可为 {@code null}
     * @param sink         输出捕获通道
     * @return 工具结果，可为 {@code null}（工具返回 {@code null} 时）
     */
    private ToolCallResult invokeTool(Session session, CancellationToken cancellation, String toolCallId,
                                      String toolName, Map<String, Object> arguments, ToolOutputSink sink) {
        PermissionDecision decision = permissionManager.decide(new PermissionCheckRequest(session.getAgentId(),
                toolName, arguments, session.getPermissionMode(), session.getSessionId()));
        if (decision.isDenied()) {
            return new ToolCallResult(toolName, "权限拒绝：" + messageOf(decision.getReason()));
        }
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler;
        try {
            handler = extensions.handler(ToolCallRequest.class, toolName);
        } catch (ExtensionException e) {
            if (e.getCode() == ExtensionException.Code.NO_HANDLER) {
                return new ToolCallResult(toolName, "未知工具：" + toolName);
            }
            return new ToolCallResult(toolName, "工具注册冲突：" + toolName);
        }
        return extensions.invoke(handler,
                new ToolCallRequest(toolName, arguments, session.getSessionId(), cancellation, sink));
    }

    /**
     * 组装工具失败时的回灌文本。
     * <p>
     * <b>已捕获的输出不能丢</b>：命令跑到一半失败（或超时、被取消）时，已经产出的那部分输出
     * 往往是排查失败的唯一线索。因此先收尾捕获通道，把它的结果拼在错误信息前面，
     * 而不是让「工具执行失败」这一句话盖掉全部现场。
     *
     * @param sink  输出捕获通道，可为 {@code null}
     * @param error 失败原因
     * @return 回灌文本，保证非 {@code null}
     */
    private static String failureText(ToolOutputSink sink, RuntimeException error) {
        String message = "工具执行失败：" + messageOf(error);
        if (sink == null) {
            return message;
        }
        String captured;
        try {
            captured = sink.finish();
        } catch (RuntimeException e) {
            LOG.warn("工具输出收尾失败，仅回灌错误信息", e);
            return message;
        }
        if (captured == null || captured.isEmpty()) {
            return message;
        }
        return captured + "\n[" + message + "]";
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
}
