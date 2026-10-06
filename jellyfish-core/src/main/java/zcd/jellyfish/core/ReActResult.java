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
 * 除终态之外还可以带一句 {@link #getNotice() 提示}：它回答的是「这次收敛有没有需要你知道的例外」——
 * 例如<b>回复被输出上限截断</b>（形状与正常答完完全一样，用户分不出来）、或<b>模型这次一个字都没回</b>。
 * 它不是错误（回合确实结束了），也不该被塞进正文冒充模型的话，因此单独一个字段，由外壳走自己的提示通道。
 * <p>
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

    /** 收敛时附带的一句提示，无需提示时为 {@code null}。 */
    private final String notice;

    /**
     * 构造结果。
     *
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param truncated 是否截断
     * @param cancelled 是否取消
     * @param blocked   是否被拦下
     * @param notice    收敛时的提示，可为 {@code null}
     */
    private ReActResult(String sessionId, String content, int rounds, boolean truncated, boolean cancelled,
                        boolean blocked, String notice) {
        this.sessionId = sessionId;
        this.content = content;
        this.rounds = rounds;
        this.truncated = truncated;
        this.cancelled = cancelled;
        this.blocked = blocked;
        this.notice = notice;
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
        return new ReActResult(sessionId, content, rounds, false, false, false, null);
    }

    /**
     * 构造带提示的「正常完成」结果。
     * <p>
     * <b>什么时候用它</b>：回合确实收敛了（模型不会再说话了），但结果里有一处用户需要知道、
     * 且<b>从正文看不出来</b>的例外——目前两处：回复被输出上限截断、模型一个字都没回。
     *
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param notice    收敛时的提示，可为 {@code null}
     * @return 结果
     */
    public static ReActResult completed(String sessionId, String content, int rounds, String notice) {
        return new ReActResult(sessionId, content, rounds, false, false, false, notice);
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
        return new ReActResult(sessionId, content, rounds, true, false, false, null);
    }

    /**
     * 构造「已取消」结果。
     *
     * @param sessionId 会话标识
     * @param rounds    实际轮数
     * @return 结果
     */
    public static ReActResult cancelled(String sessionId, int rounds) {
        return new ReActResult(sessionId, null, rounds, false, true, false, null);
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
        return new ReActResult(sessionId, reason, 0, false, false, true, null);
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

    /**
     * 获取收敛时附带的提示。
     * <p>
     * <b>它与 {@link #getContent()} 是两回事</b>：正文是模型说的话，提示是内核对这次收敛的补充说明。
     * 外壳应当走自己的提示通道（TUI 的提示行、{@code -cli} 的 stderr、Server 的 SSE 字段），
     * <b>而不是把它拼进正文</b>——那会让用户以为模型说过这句话，也会污染后续的会话历史。
     *
     * @return 提示文本，无需提示时为 {@code null}
     */
    public String getNotice() {
        return notice;
    }

    @Override
    public String toString() {
        return "ReActResult{sessionId=" + sessionId + ", rounds=" + rounds
                + ", truncated=" + truncated + ", cancelled=" + cancelled + ", blocked=" + blocked
                + ", notice=" + (notice == null ? "none" : "set") + '}';
    }
}
