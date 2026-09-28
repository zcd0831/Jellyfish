package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用请求：ReAct 循环请求执行一次工具调用，由内核按工具名路由。
 * <p>
 * 路由键即工具名，因此每个工具对应一个处理器（{@code PluginContext.handle}）。
 * <p>
 * <b>请求带两类「调用期设施」</b>，都是把内核在本次调用里能提供的能力交给工具，而不是让工具自己去找：
 * <ul>
 *     <li>{@link #getCancellationToken()}：取消信号。长时间阻塞的工具（命令行）靠它才能在用户按下 Esc
 *     时被及时终止——同步派发不会中断正在执行的工具，只能靠工具自己响应；</li>
 *     <li>{@link #getOutputSink()}：输出捕获。无界且不可再取的输出（命令行的流）交给内核边捕获边落盘，
 *     工具因此不需要自己物化整份输出，也不需要知道落盘路径与清理策略。</li>
 * </ul>
 * 两者都有「不参与」的缺省值，因此不使用它们的工具（绝大多数）行为与引入之前完全一致。
 *
 * @author zcd
 */
public final class ToolCallRequest extends ExtensionRequest<ToolCallResult> {

    /** 工具名，也是路由键。 */
    private final String toolName;

    /** 工具参数，只读。 */
    private final Map<String, Object> arguments;

    /** 取消令牌，未提供时为 {@link CancellationToken#NONE}。 */
    private final CancellationToken cancellationToken;

    /** 输出捕获通道，未提供时为 {@link ToolOutputSink#NOOP}。 */
    private final ToolOutputSink outputSink;

    /**
     * 构造工具调用请求。
     *
     * @param toolName          工具名，不可为空白
     * @param arguments         工具参数，可为 {@code null}
     * @param sessionId         会话标识，可为 {@code null}
     * @param cancellationToken 取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param outputSink        输出捕获通道，可为 {@code null}（按 {@link ToolOutputSink#NOOP} 处理）
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId,
                          CancellationToken cancellationToken, ToolOutputSink outputSink) {
        super(ToolCallResult.class, sessionId);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
        this.outputSink = outputSink == null ? ToolOutputSink.NOOP : outputSink;
    }

    /**
     * 构造工具调用请求（无取消令牌与输出捕获）。
     * <p>
     * 保留它是为了源码兼容：绝大多数调用点（测试与内部工具）两者都用不上，让它们被迫多写两个
     * {@code null} 只会把构造噪音扩散到整个仓库。
     *
     * @param toolName  工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId) {
        this(toolName, arguments, sessionId, null, null);
    }

    /**
     * 构造进程级工具调用请求。
     *
     * @param toolName  工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments) {
        this(toolName, arguments, null, null, null);
    }

    @Override
    public String getRouteKey() {
        return toolName;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取取消令牌。
     *
     * @return 取消令牌，保证非 {@code null}（未提供时为 {@link CancellationToken#NONE}）
     */
    public CancellationToken getCancellationToken() {
        return cancellationToken;
    }

    /**
     * 获取输出捕获通道。
     *
     * @return 输出捕获通道，保证非 {@code null}（未提供时为 {@link ToolOutputSink#NOOP}）
     */
    public ToolOutputSink getOutputSink() {
        return outputSink;
    }
}
