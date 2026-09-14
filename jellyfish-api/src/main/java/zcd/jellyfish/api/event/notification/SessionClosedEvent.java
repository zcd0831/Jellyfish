package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话关闭事件：会话从会话表移除后广播，供指标与 UI 收敛会话视图。
 * <p>
 * 携带关闭时的快照字段（{@code agentId} / {@code messageCount}）而不是让订阅者回查会话：
 * 事件发出时会话已不在表里，回查必然落空。
 * <p>
 * 与其他通知一样走异步、可丢弃通道；「会话已关闭」的可靠语义不建立在本事件上。
 *
 * @author zcd
 */
public final class SessionClosedEvent extends AbstractJellyfishEvent {

    /** 关闭时绑定的 agentId，未绑定时为 {@code null}。 */
    private final String agentId;

    /** 关闭时的消息条数。 */
    private final int messageCount;

    /**
     * 构造会话关闭事件。
     *
     * @param sessionId    会话标识
     * @param agentId      关闭时的 agentId，可为 {@code null}
     * @param messageCount 关闭时的消息条数
     */
    public SessionClosedEvent(String sessionId, String agentId, int messageCount) {
        super(sessionId);
        this.agentId = agentId;
        this.messageCount = messageCount;
    }

    /**
     * 获取关闭时的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取关闭时的消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }
}
