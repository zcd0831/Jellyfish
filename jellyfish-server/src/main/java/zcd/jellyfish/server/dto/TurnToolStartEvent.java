package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code tool_start} 的载荷：一次工具调用开始。
 * <p>
 * 只有 {@code toolCallId} 与工具名——参数此刻可能还在模型增量里拼装，真正的完整调用以 {@code tool_done}
 * 为准。前端据此显示「正在执行 xxx」，不需要知道参数。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnToolStartEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 工具调用标识。 */
    private final String toolCallId;

    /** 工具名。 */
    private final String toolName;

    /**
     * 构造事件。
     *
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     */
    public TurnToolStartEvent(String turnId, String toolCallId, String toolName) {
        this.turnId = turnId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
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
}
