package zcd.jellyfish.core;

/**
 * 一次 ReAct 回合的最终结果。
 * <p>
 * 三种终态，由工厂方法区分：
 * <ul>
 *     <li>{@link #completed}：模型给出了不含工具调用的最终回复；</li>
 *     <li>{@link #truncated}：达到最大轮次仍未收敛，内容是可读提示；</li>
 *     <li>{@link #cancelled}：调用方取消了本回合。</li>
 * </ul>
 * 不可变值对象，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ReActResult {

    /** 会话标识。 */
    private final String sessionId;

    /** 最终文本，取消时为 {@code null}。 */
    private final String content;

    /** 实际发生的轮数。 */
    private final int rounds;

    /** 是否因达到最大轮次而截断。 */
    private final boolean truncated;

    /** 是否被取消。 */
    private final boolean cancelled;

    /**
     * 构造结果。
     *
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param truncated 是否截断
     * @param cancelled 是否取消
     */
    private ReActResult(String sessionId, String content, int rounds, boolean truncated, boolean cancelled) {
        this.sessionId = sessionId;
        this.content = content;
        this.rounds = rounds;
        this.truncated = truncated;
        this.cancelled = cancelled;
    }

    /**
     * 构造「正常完成」结果。
     *
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @return 结果
     */
    public static ReActResult completed(String sessionId, String content, int rounds) {
        return new ReActResult(sessionId, content, rounds, false, false);
    }

    /**
     * 构造「达到最大轮次」结果。
     *
     * @param sessionId 会话标识
     * @param content   可读提示
     * @param rounds    实际轮数
     * @return 结果
     */
    public static ReActResult truncated(String sessionId, String content, int rounds) {
        return new ReActResult(sessionId, content, rounds, true, false);
    }

    /**
     * 构造「已取消」结果。
     *
     * @param sessionId 会话标识
     * @param rounds    实际轮数
     * @return 结果
     */
    public static ReActResult cancelled(String sessionId, int rounds) {
        return new ReActResult(sessionId, null, rounds, false, true);
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
     * @return 最终文本，取消时为 {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取实际轮数。
     *
     * @return 实际发生的轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 判断是否因达到最大轮次而截断。
     *
     * @return 截断返回 {@code true}
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 判断是否被取消。
     *
     * @return 取消返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "ReActResult{sessionId=" + sessionId + ", rounds=" + rounds
                + ", truncated=" + truncated + ", cancelled=" + cancelled + '}';
    }
}
