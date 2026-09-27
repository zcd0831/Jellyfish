package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code text} 的载荷：一段模型正文增量。
 * <p>
 * 前端按到达顺序拼接 {@code delta} 即为最终回答；空串增量不会下发。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnTextEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 正文增量。 */
    private final String delta;

    /**
     * 构造事件。
     *
     * @param turnId 回合标识
     * @param delta  正文增量
     */
    public TurnTextEvent(String turnId, String delta) {
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
     * 获取正文增量。
     *
     * @return 正文增量
     */
    public String getDelta() {
        return delta;
    }
}
