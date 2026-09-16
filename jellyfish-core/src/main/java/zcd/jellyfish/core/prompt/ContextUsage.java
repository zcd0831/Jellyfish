package zcd.jellyfish.core.prompt;

/**
 * 一次请求的上下文用量：把「离上下文窗口还有多远」这件事从组装过程里带出来。
 * <p>
 * <b>为什么要有它</b>：压缩的触发判据必须与裁剪的判据同源——同一个 {@link TokenEstimator}、
 * 同一个预算公式。若让压缩自己另算一遍，两处会在边界情形给出相反结论（一个说还很宽裕、
 * 一个已经开始丢历史），而用户看到的是「没压却莫名丢了几条消息」。
 * 因此用量由组装请求的那一次计算顺便产出，压缩直接读它。
 * <p>
 * <b>为什么带 {@link #isTruncated()}</b>：它是「历史正在被静默丢弃」的既成事实。
 * 比例是预防信号，这个标志是报警信号——比比例更该触发一次压缩。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ContextUsage {

    /** 已用 token 的估算值（system prompt + 实际发出的历史消息）。 */
    private final int usedTokens;

    /** 可用 token 预算；{@code <= 0} 表示模型没配上下文窗口、无从判断。 */
    private final int budgetTokens;

    /** 本次请求是否已经发生了机械裁剪。 */
    private final boolean truncated;

    /**
     * 构造上下文用量。
     *
     * @param usedTokens   已用 token 估算值，负数按 0 处理
     * @param budgetTokens 可用 token 预算，{@code <= 0} 表示无从判断
     * @param truncated    本次请求是否已发生机械裁剪
     */
    public ContextUsage(int usedTokens, int budgetTokens, boolean truncated) {
        this.usedTokens = Math.max(0, usedTokens);
        this.budgetTokens = budgetTokens;
        this.truncated = truncated;
    }

    /**
     * 构造「无从判断」的用量：模型没配上下文窗口时用它。
     *
     * @return 用量，比例判定恒为假
     */
    public static ContextUsage unknown() {
        return new ContextUsage(0, 0, false);
    }

    /**
     * 获取已用 token 估算值。
     *
     * @return 估算值，非负
     */
    public int getUsedTokens() {
        return usedTokens;
    }

    /**
     * 获取可用 token 预算。
     *
     * @return 预算；{@code <= 0} 表示模型没配上下文窗口
     */
    public int getBudgetTokens() {
        return budgetTokens;
    }

    /**
     * 判断本次请求是否已发生机械裁剪。
     *
     * @return 已裁剪返回 {@code true}
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * 判断用量是否达到指定百分比。
     * <p>
     * 用整数乘法而不是浮点除法：{@code (long) used * 100} 不会溢出，也避免
     * {@code 0.8} 这类二进制小数在边界上带来的「80% 到底算不算到」的争议。
     *
     * @param percent 百分比，{@code <= 0} 或无从判断时返回 {@code false}
     * @return 达到或超过该百分比返回 {@code true}
     */
    public boolean exceeds(int percent) {
        if (percent <= 0 || budgetTokens <= 0) {
            return false;
        }
        return (long) usedTokens * 100 >= (long) budgetTokens * percent;
    }

    @Override
    public String toString() {
        return "ContextUsage{usedTokens=" + usedTokens + ", budgetTokens=" + budgetTokens
                + ", truncated=" + truncated + '}';
    }
}
