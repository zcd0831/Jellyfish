package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话创建事件：会话运行态初始化完成后广播，供指标与 UI 观察会话生命周期。
 * <p>
 * <b>{@code parentSessionId} 非 {@code null} 即子代理会话</b>：子代理的会话同样是真实会话，
 * 因此它的事件照发；区分它靠这个字段而不是靠「不发事件」——后者会让「子代理在跑」在
 * 指标与界面里彻底隐形，而它恰恰是最需要被看见的一段。
 *
 * @author zcd
 */
public final class SessionCreatedEvent extends AbstractJellyfishEvent {

    /** 创建该会话时使用的 agentId。 */
    private final String agentId;

    /** 派生该会话的父会话标识，{@code null} 表示不是子代理会话（根会话）。 */
    private final String parentSessionId;

    /**
     * 构造根会话的创建事件。
     *
     * @param agentId   agentId，可为 {@code null}
     * @param sessionId 会话标识
     */
    public SessionCreatedEvent(String agentId, String sessionId) {
        this(agentId, sessionId, null);
    }

    /**
     * 构造会话创建事件。
     *
     * @param agentId         agentId，可为 {@code null}
     * @param sessionId       会话标识
     * @param parentSessionId 派生该会话的父会话标识，{@code null} 表示根会话
     */
    public SessionCreatedEvent(String agentId, String sessionId, String parentSessionId) {
        super(sessionId);
        this.agentId = agentId;
        this.parentSessionId = parentSessionId;
    }

    /**
     * 获取 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取派生该会话的父会话标识。
     *
     * @return 父会话标识；非子代理会话时为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }
}
