package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code tool_output} 的载荷：工具执行期的一段实时输出。
 * <p>
 * <b>它是可丢的过程信息，不是权威文本</b>：真正的结果在随后的 {@code tool_done} 里，那段
 * {@code output} 由内核统一截断与落盘，与本事件无关。客户端应当把这里的内容当成「正在发生的
 * 动静」来展示，而不是当成结果——一条命令跑十分钟而中间一条事件都没收到，用户会以为它卡死了。
 * <p>
 * <b>为什么要带 {@code toolCallId}</b>：并发工具、连续多次调用都会让「当前是哪个工具在输出」
 * 变得不确定，而同一个回合里工具调用是串行的——但这属于内核的当前实现，客户端不该依赖它。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnToolOutputEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 工具调用标识。 */
    private final String toolCallId;

    /** 工具名。 */
    private final String toolName;

    /** 本次新增的输出片段。 */
    private final String chunk;

    /**
     * 构造事件。
     *
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param chunk      输出片段
     */
    public TurnToolOutputEvent(String turnId, String toolCallId, String toolName, String chunk) {
        this.turnId = turnId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.chunk = chunk;
    }

    /**
     * 获取回合标识。
     *
     * @return 回合标识
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * 获取工具调用标识。
     *
     * @return 工具调用标识
     */
    public String getToolCallId() {
        return toolCallId;
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
     * 获取输出片段。
     *
     * @return 输出片段
     */
    public String getChunk() {
        return chunk;
    }
}
