package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 工具调用完成事件：工具执行结束后广播，用于指标、审计与 UI 收尾。
 * <p>
 * 失败也走通知通道（{@link #isSuccess()} 为 {@code false}），便于订阅方统一记账；
 * 与命令通道不同，通知不携带返回值，失败详情由 {@link #getErrorMessage()} 提供。
 *
 * @author zcd
 */
public final class ToolCallCompletedEvent extends AbstractJellyfishEvent {

    /** 工具调用标识，与对应的开始事件配对。 */
    private final String toolCallId;

    /** 工具名。 */
    private final String toolName;

    /** 是否成功。 */
    private final boolean success;

    /** 耗时（毫秒）。 */
    private final long durationMillis;

    /** 失败原因，成功时为 {@code null}。 */
    private final String errorMessage;

    /**
     * 构造工具调用完成事件。
     *
     * @param toolCallId     工具调用标识
     * @param toolName       工具名
     * @param success        是否成功
     * @param durationMillis 耗时（毫秒）
     * @param errorMessage   失败原因，可为 {@code null}
     * @param sessionId      会话标识，可为 {@code null}
     */
    public ToolCallCompletedEvent(String toolCallId, String toolName, boolean success, long durationMillis,
                                  String errorMessage, String sessionId) {
        super(sessionId);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.success = success;
        this.durationMillis = durationMillis;
        this.errorMessage = errorMessage;
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
     * 判断调用是否成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取耗时。
     *
     * @return 耗时（毫秒）
     */
    public long getDurationMillis() {
        return durationMillis;
    }

    /**
     * 获取失败原因。
     *
     * @return 失败原因，成功时为 {@code null}
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
