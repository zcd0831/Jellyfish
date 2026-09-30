package zcd.jellyfish.core;

/**
 * 一次 ReAct 回合的最终结果。
 * <p>
 * 四种终态，由工厂方法区分：
 * <ul>
 *     <li>{@link #completed}：模型给出了不含工具调用的最终回复；</li>
 *     <li>{@link #truncated}：达到最大轮次仍未收敛，内容是可读提示；</li>
 *     <li>{@link #cancelled}：调用方取消了本回合；</li>
 *     <li>{@link #blocked}：插件在回合开始前拦下了它，<b>用户消息根本未进会话</b>。</li>
 * </ul>
 * 不可变值对象，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ReActResult {

    /** 会话标识。 */
    private final String sessionId;

    /** 最终文本；取消时为 {@code null}，被拦下时为拦下的理由。 */
    private final String content;

    /** 实际发生的轮数。 */
    private final int rounds;

    /** 是否因达到最大轮次而截断。 */
    private final boolean truncated;

    /** 是否被取消。 */
    private final boolean cancelled;

    /** 是否被插件拦下。 */
    private final boolean blocked;

    /**
     * 构造结果。
     *
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param truncated 是否截断
     * @param cancelled 是否取消
     * @param blocked   是否被拦下
     */
    private ReActResult(String sessionId, String content, int rounds, boolean truncated, boolean cancelled,
                        boolean blocked) {
        this.sessionId = sessionId;
        this.content = content;
        this.rounds = rounds;
        this.truncated = truncated;
        this.cancelled = cancelled;
        this.blocked = blocked;
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
        return new ReActResult(sessionId, content, rounds, false, false, false);
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
        return new ReActResult(sessionId, content, rounds, true, false, false);
    }

    /**
     * 构造「已取消」结果。
     *
     * @param sessionId 会话标识
     * @param rounds    实际轮数
     * @return 结果
     */
    public static ReActResult cancelled(String sessionId, int rounds) {
        return new ReActResult(sessionId, null, rounds, false, true, false);
    }

    /**
     * 构造「被插件拦下」结果。
     * <p>
     * <b>它不是任何一种失败，也不是取消</b>：没有异常、没有资源故障、用户也没有按 Esc。
     * 它是「插件在回合开始前拦下了这一句」，因此脚本与外壳需要把它与「跑挂了」分开
     * （{@code -cli} 为此给了一个独立的退出码）。
     * <p>
     * <b>会话没有任何变化</b>：拦下发生在追加用户消息之前，因此历史里不会多出一条用户消息、
     * 也不会伪造一条 assistant 消息——用户重发一次就好。
     *
     * @param sessionId 会话标识
     * @param reason    拦下的理由，可为 {@code null}（外壳侧写固定占位）
     * @return 结果
     */
    public static ReActResult blocked(String sessionId, String reason) {
        return new ReActResult(sessionId, reason, 0, false, false, true);
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
     * @return 最终文本；取消时为 {@code null}，被拦下时为拦下的理由
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

    /**
     * 判断是否被插件拦下。
     *
     * @return 被拦下返回 {@code true}
     */
    public boolean isBlocked() {
        return blocked;
    }

    /**
     * 获取被拦下的理由。
     * <p>
     * 它只是 {@link #getContent()} 的语义化入口：两个方法返回同一个值，保留后者是为让「拿最终文本」
     * 与「拿拦下理由」的调用点各自读得明白。
     *
     * @return 拦下的理由，未被拦下时为 {@code null}
     */
    public String getBlockedReason() {
        return blocked ? content : null;
    }

    @Override
    public String toString() {
        return "ReActResult{sessionId=" + sessionId + ", rounds=" + rounds
                + ", truncated=" + truncated + ", cancelled=" + cancelled + ", blocked=" + blocked + '}';
    }
}
