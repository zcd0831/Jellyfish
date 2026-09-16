package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话的压缩摘要快照：{@code /compact} 把旧历史压成一段摘要后留下的那一块状态。
 * <p>
 * <b>为什么需要它</b>：压缩是<b>非破坏式</b>的——会话消息一条不删，摘要只是「哪些历史不再进请求、
 * 它们被压成了什么」的记账。这笔账必须随会话一起落盘，否则重启后模型会突然收到一整份远古历史，
 * token 立刻涨回去；而摘要本身是模型生成的、无法重算（重算要再花一次调用，且结果不会一样）。
 * <p>
 * <b>为什么是「一块摘要」而不是一串</b>：每次压缩都只压「上次边界之后新积累的那一段」，
 * 并把上一份摘要一并喂进摘要请求，因此会话里始终只有一块摘要，边界只能向后移。
 * 这就是 {@code /compact} 的滚动摘要口径，详细推导见 {@code 五项增强方案.md} §3.2.1。
 * <p>
 * <b>为什么还要记「丢了多少条」</b>：一次摘要请求本身也受同一个上下文窗口约束，
 * 待压的历史装不下时只能从最旧侧直接丢弃（单次压缩，不留到下一次）。被丢弃的那一段既不在
 * 摘要里、也不再进请求，是真正消失的数据，因此必须如实记下来：既要在 system prompt 里告诉模型
 * 「有内容我确实没看到」，也要让用户知道这一次压缩的代价。
 * <p>
 * <b>与 {@code SessionSnapshot} 的关系</b>：它是 {@code SessionSnapshot} 的一个可空字段——
 * 几个字段成组出现、缺一个都没有意义（有摘要没边界就不知道该丢哪一段），因此不向
 * {@code SessionSnapshot} 平铺。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionCompactionSnapshot {

    /** 摘要正文。 */
    private final String summary;

    /** 摘要覆盖到的最后一条消息标识：它及其之前的消息不再进请求。 */
    private final String boundaryMessageId;

    /** 摘要在本会话里最后一次被更新的时间戳（epoch millis）。 */
    private final long createdAt;

    /** 因超出摘要输入预算而被直接丢弃的条数（既不在摘要里、也不再进请求）。 */
    private final int droppedMessageCount;

    /**
     * 构造压缩摘要快照。
     * <p>
     * <b>为什么是唯一构造器 + 兼容静态工厂</b>：Jackson 用
     * {@code ParameterNamesModule} 按构造器参数名反序列化时，要求「恰好一个可见构造器」——
     * 多一个重载就会让两条路径都认不出参数名。因此新增字段只能改这一个构造器，
     * 兼容入口交给静态工厂。
     *
     * @param summary           摘要正文，不可为空白
     * @param boundaryMessageId 边界消息标识，不可为空白
     * @param createdAt         摘要更新时间戳（epoch millis）
     * @param droppedMessageCount 被直接丢弃的条数，负数按 0 处理
     * @throws JellyfishException 摘要或边界消息标识为空白时抛出
     */
    public SessionCompactionSnapshot(String summary, String boundaryMessageId, long createdAt,
                                     int droppedMessageCount) {
        if (summary == null || summary.trim().isEmpty()) {
            throw new JellyfishException("compaction summary must not be blank");
        }
        if (boundaryMessageId == null || boundaryMessageId.trim().isEmpty()) {
            throw new JellyfishException("compaction boundary message id must not be blank");
        }
        this.summary = summary;
        this.boundaryMessageId = boundaryMessageId;
        this.createdAt = createdAt;
        this.droppedMessageCount = Math.max(0, droppedMessageCount);
    }

    /**
     * 构造不带「丢弃条数」的压缩摘要快照。
     *
     * @param summary           摘要正文，不可为空白
     * @param boundaryMessageId 边界消息标识，不可为空白
     * @param createdAt         摘要更新时间戳（epoch millis）
     * @return 压缩摘要快照
     */
    public static SessionCompactionSnapshot of(String summary, String boundaryMessageId, long createdAt) {
        return new SessionCompactionSnapshot(summary, boundaryMessageId, createdAt, 0);
    }

    /**
     * 获取摘要正文。
     *
     * @return 摘要正文，保证非空白
     */
    public String getSummary() {
        return summary;
    }

    /**
     * 获取边界消息标识。
     *
     * @return 边界消息标识，保证非空白
     */
    public String getBoundaryMessageId() {
        return boundaryMessageId;
    }

    /**
     * 获取摘要更新时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取被直接丢弃的条数。
     *
     * @return 条数，非负
     */
    public int getDroppedMessageCount() {
        return droppedMessageCount;
    }

    @Override
    public String toString() {
        // 刻意不打印摘要正文：它可能很长，混进日志行会很难看
        return "SessionCompactionSnapshot{boundaryMessageId=" + boundaryMessageId
                + ", summaryLength=" + summary.length() + '}';
    }
}
