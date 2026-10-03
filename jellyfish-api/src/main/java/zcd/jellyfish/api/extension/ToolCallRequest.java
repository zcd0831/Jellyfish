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
 * <b>请求带三类「调用期设施」</b>，都是把内核在本次调用里能提供的能力交给工具，而不是让工具自己去找：
 * <ul>
 *     <li>{@link #getCancellationToken()}：取消信号。长时间阻塞的工具（命令行）靠它才能在用户按下 Esc
 *     时被及时终止——同步派发不会中断正在执行的工具，只能靠工具自己响应；</li>
 *     <li>{@link #getOutputSink()}：输出捕获。无界且不可再取的输出（命令行的流）交给内核边捕获边落盘，
 *     工具因此不需要自己物化整份输出，也不需要知道落盘路径与清理策略。</li>
 *     <li>{@link #getParentSessionId()} / {@link #getRunId()} / {@link #getRootRunId()}：<b>调用者身份</b>。
 *     子代理有独立的会话与 run，工具却常常需要知道「我此刻在替谁干活」——协作状态该落在哪个会话上、
 *     这次改动属于哪一次委派。这些答案只有内核知道，而且是<b>线程作用域</b>的（同一条线程上正在跑的就是
 *     这一个 run），因此随请求交给工具，而不是让工具去一个全局访问器里问。</li>
 * </ul>
 * 三者都有「不参与」的缺省值（取消令牌、空输出通道、身份全为 {@code null}），
 * 因此不使用它们的工具（绝大多数）行为与引入之前完全一致。
 * <p>
 * <b>身份字段与 {@code Session} 的同名字段同口径</b>：{@code parentSessionId} 就是派生本次会话的那个会话，
 * 根会话因此为 {@code null}——「这次调用属于哪个协作空间」的完整写法是
 * {@code parent != null ? parent : sessionId}，由使用方按需拼出，内核不预设这个语义。
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

    /** 派生本次会话的那个会话；根会话为 {@code null}。 */
    private final String parentSessionId;

    /** 本次调用所在的 run；不在任何 run 上时为 {@code null}。 */
    private final String runId;

    /** 本次调用所在的 run 树根；不在任何 run 上时为 {@code null}。 */
    private final String rootRunId;

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
        this(toolName, arguments, sessionId, cancellationToken, outputSink, null, null, null);
    }

    /**
     * 构造带调用者身份的工具调用请求。
     * <p>
     * 这是内核组装工具调用的入口：三个身份字段都由内核从当前执行路径推出（会话的派生来源 +
     * 当前 run 上下文），插件不参与填写，模型更无从指定——协作键因此不可能被诱导写偏。
     *
     * @param toolName          工具名，不可为空白
     * @param arguments         工具参数，可为 {@code null}
     * @param sessionId         会话标识，可为 {@code null}
     * @param cancellationToken 取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param outputSink        输出捕获通道，可为 {@code null}（按 {@link ToolOutputSink#NOOP} 处理）
     * @param parentSessionId   派生本次会话的那个会话，可为 {@code null}（根会话）
     * @param runId             本次调用所在的 run，可为 {@code null}（不在任何 run 上）
     * @param rootRunId         本次调用所在的 run 树根，可为 {@code null}（不在任何 run 上）
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId,
                          CancellationToken cancellationToken, ToolOutputSink outputSink,
                          String parentSessionId, String runId, String rootRunId) {
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
        this.parentSessionId = parentSessionId;
        this.runId = runId;
        this.rootRunId = rootRunId;
    }

    /**
     * 构造工具调用请求（无取消令牌、输出捕获与调用者身份）。
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
     * 构造进程级工具调用请求（无会话、无调用者身份）。
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

    /**
     * 获取派生本次会话的那个会话。
     *
     * @return 父会话标识；根会话（用户直接对话的那一个）为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取本次调用所在的 run。
     *
     * @return run 标识；不在任何 run 上（顶层回合、进程级调用）时为 {@code null}
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 获取本次调用所在的 run 树根。
     * <p>
     * 一次顶层回合里发起的多棵 run 树各有自己的根，因此这个值与「一次会话」不是一回事。
     *
     * @return 根 run 标识；不在任何 run 上时为 {@code null}
     */
    public String getRootRunId() {
        return rootRunId;
    }
}
