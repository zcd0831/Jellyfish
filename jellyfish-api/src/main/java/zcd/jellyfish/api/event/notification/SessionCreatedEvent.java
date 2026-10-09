package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话创建事件：会话运行态初始化完成后广播，供指标与 UI 观察会话生命周期。
 * <p>
 * <b>{@code parentSessionId} 只是「派生自哪个会话」，不是「它是不是子代理」</b>：子代理的会话同样是
 * 真实会话，因此它的事件照发（区分它靠「不发事件」会让「子代理在跑」在指标与界面里彻底隐形，
 * 而它恰恰是最需要被看见的一段）；但这个字段<b>同时</b>会出现在分支（{@code FORKED}）会话上——
 * 那个父只是「从哪条会话分出来的」这条追溯信息。判「是不是子代理会话」要看 {@code SessionKind}，
 * 不看这个字段有没有值。
 *
 * @author zcd
 */
public final class SessionCreatedEvent extends AbstractJellyfishEvent {

    /** 创建该会话时使用的 agentId。 */
    private final String agentId;

    /**
     * 派生该会话的父会话标识，{@code null} 表示不是派生出来的（根会话）。
     * <p>
     * <b>非 {@code null} 不等于子代理会话</b>：分支（{@code FORKED}）会话也带着它，那里只是追溯信息。
     */
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
     * @param parentSessionId 派生该会话的父会话标识，{@code null} 表示它没有父（子代理会话与
     *                        分支会话都会有值，两者不是一回事）
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
     * @return 父会话标识；没有父（根会话）时为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }
}
