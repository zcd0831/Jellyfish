package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code turn_start} 的载荷：回合已被受理。
 * <p>
 * 客户端拿到它就知道「流已经接上了、这个 turnId 属于这次请求」；后续事件都带同一个 turnId，
 * 便于前端把并发的多路流区分开。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnStartEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 会话标识。 */
    private final String sessionId;

    /**
     * 构造事件。
     *
     * @param turnId    回合标识
     * @param sessionId 会话标识
     */
    public TurnStartEvent(String turnId, String sessionId) {
        this.turnId = turnId;
        this.sessionId = sessionId;
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
}
