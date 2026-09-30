package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code input_handled} 的载荷：这次输入被插件接过去了，没有回合。
 * <p>
 * <b>为什么不是 {@code done}</b>：{@code done} 携带一个回合的结果，而这里根本没有回合——
 * 没有用户消息落进会话、没有模型调用、没有轮数。客户端据事件类型决定怎么展示
 * （{@code done} 的正文是回答，本事件的 {@code notice} 是外壳提示）。
 * <p>
 * <b>为什么带 sessionId</b>：它是这条流的唯一关联标识（没有 turnId 可给——回合压根没起）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputHandledEvent {

    /** 会话标识。 */
    private final String sessionId;

    /** 贴给用户的说明，可为 {@code null}。 */
    private final String notice;

    /**
     * 构造事件。
     *
     * @param sessionId 会话标识
     * @param notice    贴给用户的说明，可为 {@code null}
     */
    public InputHandledEvent(String sessionId, String notice) {
        this.sessionId = sessionId;
        this.notice = notice;
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
     * 获取贴给用户的说明。
     *
     * @return 说明，可为 {@code null}
     */
    public String getNotice() {
        return notice;
    }
}
