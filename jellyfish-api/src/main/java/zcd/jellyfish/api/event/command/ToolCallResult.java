package zcd.jellyfish.api.event.command;

/**
 * 工具调用结果。
 * <p>
 * 处理器失败时直接抛异常，因此结果对象只承载成功语义；{@code output} 由具体工具决定，
 * 通常为字符串或可序列化为 JSON 的对象。
 *
 * @author zcd
 */
public final class ToolCallResult {

    /** 工具名。 */
    private final String toolName;

    /** 工具输出，可为 {@code null}。 */
    private final Object output;

    /**
     * 构造工具调用结果。
     *
     * @param toolName 工具名
     * @param output   工具输出，可为 {@code null}
     */
    public ToolCallResult(String toolName, Object output) {
        this.toolName = toolName;
        this.output = output;
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
     * 获取工具输出。
     *
     * @return 工具输出，可能为 {@code null}
     */
    public Object getOutput() {
        return output;
    }

    @Override
    public String toString() {
        return "ToolCallResult{toolName=" + toolName + ", output=" + output + '}';
    }
}
