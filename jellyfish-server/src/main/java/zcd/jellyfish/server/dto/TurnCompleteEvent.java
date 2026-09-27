package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code done} 的载荷：回合正常收敛。
 * <p>
 * 这是终态事件：写出后流即结束。{@code content} 是最终回答（前端若已按 {@code text} 增量拼好，
 * 可直接忽略它；对只关心结果的客户端它是一次性拿到全文的机会）。{@code truncated=true} 表示
 * 达到最大轮次仍未收敛，此时 {@code content} 是一句可读提示而非模型回答。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnCompleteEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 会话标识。 */
    private final String sessionId;

    /** 最终文本，可为 {@code null}。 */
    private final String content;

    /** 实际轮数。 */
    private final int rounds;

    /** 是否因达到最大轮次而截断。 */
    private final boolean truncated;

    /**
     * 构造事件。
     *
     * @param turnId    回合标识
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param truncated 是否截断
     */
    public TurnCompleteEvent(String turnId, String sessionId, String content, int rounds, boolean truncated) {
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.content = content;
        this.rounds = rounds;
        this.truncated = truncated;
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
     * 获取最终文本。
     *
     * @return 最终文本，可能为 {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取实际轮数。
     *
     * @return 轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 判断是否截断。
     *
     * @return 截断返回 {@code true}
     */
    public boolean isTruncated() {
        return truncated;
    }
}
