package zcd.jellyfish.api.extension;

/**
 * 压缩指令：插件在 {@link CompactionPreRequest} 上能表达的结果。
 * <p>
 * 两件事：**要不要压**（{@link #cancel(String)}）与**至少保留多少条原文**（{@link #keepRecent(int)}）。
 * <p>
 * <b>为什么可以改保留条数</b>：压缩花的是模型的钱，而「这次该压多少」通常是<b>领域知识</b>——
 * 一个跑长任务的插件比内核更清楚「最近这几轮是同一个目标的连续步骤，压掉就断了」。
 * 内核给的策略是通用缺省，插件能给的是贴合场景的值。
 * <p>
 * <b>为什么不能给一份摘要正文</b>：那意味着插件要自己调模型，而插件今天没有模型调用能力。
 * 「插件提供摘要正文」在 provider 层开放之前是明确不做的。
 * <p>
 * <b>越界会被钳制</b>：内核照旧按 {@code [0, 消息总数]} 与工具调用组对齐规则处理，
 * 传一个离谱的值不会破坏配对不变量（那是厂商 400 的直接来源）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionDirective {

    /** 单例：放行且不改保留条数。 */
    private static final CompactionDirective PROCEED = new CompactionDirective(false, null, null);

    /** 是否拦下本次压缩。 */
    private final boolean cancelled;

    /** 拦下的理由，可为 {@code null}。 */
    private final String reason;

    /** 保留条数覆盖值，{@code null} 表示不改。 */
    private final Integer keepRecent;

    /**
     * 构造指令。
     *
     * @param cancelled  是否拦下
     * @param reason     拦下的理由，可为 {@code null}
     * @param keepRecent 保留条数覆盖值，可为 {@code null}（表示不改）
     */
    public CompactionDirective(boolean cancelled, String reason, Integer keepRecent) {
        this.cancelled = cancelled;
        this.reason = reason;
        this.keepRecent = keepRecent;
    }

    /**
     * 构造「放行且不改保留条数」指令。
     *
     * @return 指令
     */
    public static CompactionDirective proceed() {
        return PROCEED;
    }

    /**
     * 构造「拦下本次压缩」指令。
     *
     * @param reason 拦下的理由，可为 {@code null}
     * @return 指令
     */
    public static CompactionDirective cancel(String reason) {
        return new CompactionDirective(true, reason, null);
    }

    /**
     * 构造「改保留条数」指令。
     * <p>
     * 它与「拦下」互斥：想拦下就不该再谈保留多少条（那一次压根不压）。
     *
     * @param keepRecent 保留条数，可为任意整数（越界由内核钳制）
     * @return 指令
     */
    public static CompactionDirective keepRecent(int keepRecent) {
        return new CompactionDirective(false, null, Integer.valueOf(keepRecent));
    }

    /**
     * 判断是否拦下。
     *
     * @return 拦下返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 获取拦下的理由。
     *
     * @return 理由，未拦下时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 判断是否要改保留条数。
     *
     * @return 要改返回 {@code true}
     */
    public boolean hasKeepRecent() {
        return !cancelled && keepRecent != null;
    }

    /**
     * 获取保留条数覆盖值。
     *
     * @return 覆盖值，未指定时为 {@code null}
     */
    public Integer getKeepRecent() {
        return keepRecent;
    }

    @Override
    public String toString() {
        return "CompactionDirective{cancelled=" + cancelled + ", reason=" + reason
                + ", keepRecent=" + keepRecent + '}';
    }
}
