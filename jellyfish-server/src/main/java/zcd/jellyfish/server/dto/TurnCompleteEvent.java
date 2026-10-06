package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code done} 的载荷：回合正常收敛。
 * <p>
 * 这是终态事件：写出后流即结束。{@code content} 是最终回答（前端若已按 {@code text} 增量拼好，
 * 可直接忽略它；对只关心结果的客户端它是一次性拿到全文的机会）。{@code truncated=true} 表示
 * 达到最大轮次仍未收敛，此时 {@code content} 是一句可读提示而非模型回答。
 * <p>
 * {@code notice} 是内核补的一句提示，回答「这次收敛有没有从正文看不出来的例外」——
 * 目前两处：回复被输出上限截断（{@code content} 看起来是一句没说完的话，但形状与正常答完一样）、
 * 模型一个字都没回。它是 {@code null} 时表示没有需要说明的例外。<b>它不是回答</b>：
 * 前端应当走自己的提示样式渲染，而不是拼进 {@code content} 之后当正文显示。
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

    /** 收敛时的提示（被输出上限截断 / 模型未给出回复），无则 {@code null}。 */
    private final String notice;

    /**
     * 构造事件。
     *
     * @param turnId    回合标识
     * @param sessionId 会话标识
     * @param content   最终文本，可为 {@code null}
     * @param rounds    实际轮数
     * @param truncated 是否截断
     * @param notice    收敛时的提示，可为 {@code null}
     */
    public TurnCompleteEvent(String turnId, String sessionId, String content, int rounds, boolean truncated,
                             String notice) {
        this.turnId = turnId;
        this.sessionId = sessionId;
        this.content = content;
        this.rounds = rounds;
        this.truncated = truncated;
        this.notice = notice;
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

    /**
     * 获取收敛时的提示。
     * <p>
     * 有值时它是一句给用户看的说明（回复被输出上限截断、或模型一个字都没回），
     * 前端应当用提示样式渲染，<b>不要拼进 {@link #getContent()} 当正文</b>。
     *
     * @return 提示文本，无则为 {@code null}
     */
    public String getNotice() {
        return notice;
    }
}
