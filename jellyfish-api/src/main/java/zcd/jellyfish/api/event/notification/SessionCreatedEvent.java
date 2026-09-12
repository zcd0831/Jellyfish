package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话创建事件：会话运行态初始化完成后广播，供指标与 UI 观察会话生命周期。
 *
 * @author zcd
 */
public final class SessionCreatedEvent extends AbstractJellyfishEvent {

    /** 创建该会话时使用的 agentId。 */
    private final String agentId;

    /**
     * 构造会话创建事件。
     *
     * @param agentId   agentId，可为 {@code null}
     * @param sessionId 会话标识
     */
    public SessionCreatedEvent(String agentId, String sessionId) {
        super(sessionId);
        this.agentId = agentId;
    }

    /**
     * 获取 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }
}
