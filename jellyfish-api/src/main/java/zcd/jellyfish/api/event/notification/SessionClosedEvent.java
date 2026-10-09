package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话关闭事件：会话从会话表移除后广播，供指标与 UI 收敛会话视图。
 * <p>
 * 携带关闭时的快照字段（{@code agentId} / {@code messageCount}）而不是让订阅者回查会话：
 * 事件发出时会话已不在表里，回查必然落空。
 * <p>
 * <b>{@code parentSessionId} 只是一条追溯信息，不能拿它当「是不是子代理会话」</b>：子代理会话关闭时
 * 事件照发，订阅者靠这个字段把它归到父会话而不是当成一个根会话消失了；但分支（{@code FORKED}）会话
 * 同样带着父。会话种类看 {@code SessionKind}，不看这个字段有没有值。
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
     * 派生该会话的父会话标识，{@code null} 表示它没有父（根会话）。
     * <p>
     * <b>非 {@code null} 不等于子代理会话</b>：分支（{@code FORKED}）会话也带着它，那里只是追溯信息。
     */
    private final String parentSessionId;

    /**
     * 构造根会话的关闭事件。
     *
     * @param sessionId    会话标识
     * @param agentId      关闭时的 agentId，可为 {@code null}
     * @param messageCount 关闭时的消息条数
     */
    public SessionClosedEvent(String sessionId, String agentId, int messageCount) {
        this(sessionId, agentId, messageCount, null);
    }

    /**
     * 构造会话关闭事件。
     *
     * @param sessionId       会话标识
     * @param agentId         关闭时的 agentId，可为 {@code null}
     * @param messageCount    关闭时的消息条数
     * @param parentSessionId 派生该会话的父会话标识，{@code null} 表示它没有父（子代理会话与
     *                        分支会话都会有值，两者不是一回事）
     */
    public SessionClosedEvent(String sessionId, String agentId, int messageCount, String parentSessionId) {
        super(sessionId);
        this.agentId = agentId;
        this.messageCount = messageCount;
        this.parentSessionId = parentSessionId;
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

    /**
     * 获取派生该会话的父会话标识。
     *
     * @return 父会话标识；没有父（根会话）时为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }
}
