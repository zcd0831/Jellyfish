package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code thinking} 的载荷：一段思考过程增量。
 * <p>
 * 思考与正文分成两个事件名，而不是打包成一个「带 kind 的文本事件」：前端是否展示思考
 * 是一个视图偏好，分成两个事件名之后，前端可以直接忽略 {@code thinking} 而不必解析每条文本。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnThinkingEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 思考增量。 */
    private final String delta;

    /**
     * 构造事件。
     *
     * @param turnId 回合标识
     * @param delta  思考增量
     */
    public TurnThinkingEvent(String turnId, String delta) {
        this.turnId = turnId;
        this.delta = delta;
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
     * 获取思考增量。
     *
     * @return 思考增量
     */
    public String getDelta() {
        return delta;
    }
}
