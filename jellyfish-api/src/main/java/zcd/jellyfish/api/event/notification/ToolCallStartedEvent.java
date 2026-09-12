package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 工具调用开始事件：命令通道真正开始执行工具时广播，用于埋点与进度提示。
 *
 * @author zcd
 */
public final class ToolCallStartedEvent extends AbstractJellyfishEvent {

    /** 工具调用标识，与对应的完成事件配对。 */
    private final String toolCallId;

    /** 工具名，也是命令路由键。 */
    private final String toolName;

    /**
     * 构造工具调用开始事件。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param sessionId  会话标识，可为 {@code null}
     */
    public ToolCallStartedEvent(String toolCallId, String toolName, String sessionId) {
        super(sessionId);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
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
