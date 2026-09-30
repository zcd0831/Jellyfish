package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code turn_blocked} 的载荷：回合在开始前被插件拦下。
 * <p>
 * <b>为什么单独一档终态而不是复用 {@code error}</b>：它不是错误——没抛异常、没有资源故障，
 * 客户端也没有断开。它是「这次请求被策略拦下了」，客户端对它的处理与对错误完全不同
 * （提示用户改请求或找管理员，而不是重试 / 报障）。{@code -cli} 为此也给了独立的退出码。
 * <p>
 * <b>为什么带 reason 而 {@code cancelled} 不带</b>：取消不需要理由（用户自己按的），
 * 而拦下必须说清是谁按什么规则拦的，否则用户无从修正。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnBlockedEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 会话标识。 */
    private final String sessionId;

    /** 拦下的理由，可为 {@code null}。 */
    private final String reason;

    /**
     * 构造事件。
     *
     * @param turnId    回合标识
     * @param sessionId 会话标识
     * @param reason    拦下的理由，可为 {@code null}
     */
    public TurnBlockedEvent(String turnId, String sessionId, String reason) {
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.reason = reason;
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
     * 获取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取拦下的理由。
     *
     * @return 理由，可为 {@code null}
     */
    public String getReason() {
        return reason;
    }
}
