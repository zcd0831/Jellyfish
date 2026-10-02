package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code cancelled} 的载荷：回合被取消。
 * <p>
 * 取消可能来自客户端断开、{@code POST /sessions/{id}/cancel}，或进程关闭。无论哪种，
 * 语义都是「本次回合没有给出完整回答」。
 * <p>
 * <b>为什么没有 rounds</b>：内核的 {@code ShellTurnEvent.Kind#CANCELLED} 不带参数，而取消时的轮数只存在于
 * 最终结果对象里——本流是流式的，不会去 {@code await()}。与其编一个数字，不如不给。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnCancelledEvent {

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
    public TurnCancelledEvent(String turnId, String sessionId) {
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
