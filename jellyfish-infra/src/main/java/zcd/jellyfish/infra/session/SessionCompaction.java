package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.JellyfishException;

/**
 * 会话的压缩摘要：哪些历史不再进请求、它们被压成了什么。
 * <p>
 * <b>为什么是「一块」而不是一串</b>：每次压缩只压「上次边界之后新积累的那一段」，并把上一份摘要
 * 一并喂进摘要请求，因此会话里始终只有一块摘要、边界只能向后移（滚动摘要）。
 * <p>
 * <b>为什么偏要记「丢了多少条」而不能现算</b>：边界之前的条数可以现算（{@link Session#indexOfMessage(String)}），
 * 但「其中有多少条是被直接丢弃、没进摘要」不行——那取决于压缩当时的预算，事后无从推断。
 * 而它又是一个必须说的话：那段历史既不在摘要里也不再进请求，是真正消失的数据，
 * 模型（“有内容我没看到”）与用户（“这次的代价是多少”）都得知道。
 * <p>
 * <b>不在这里记「压了多少条」</b>：
 * 条数可由 {@link Session#indexOfMessage(String)} 现算。
 * 存一份副本就多一个可能与消息列表对不上的字段——落地多一份状态，就多一处在恢复 / 导入 / 手工改文件
 * 之后会撒谎的地方。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionCompaction {

    /** 摘要正文。 */
    private final String summary;

    /** 边界消息标识：它及其之前的消息不再进请求。 */
    private final String boundaryMessageId;

    /** 摘要更新时间戳（epoch millis）。 */
    private final long createdAt;

    /** 因超出摘要输入预算而被直接丢弃的条数。 */
    private final int droppedMessageCount;

    /**
     * 构造压缩摘要。
     *
     * @param summary           摘要正文，不可为空白
     * @param boundaryMessageId 边界消息标识，不可为空白
     * @param createdAt         摘要更新时间戳（epoch millis）
     * @param droppedMessageCount 被直接丢弃的条数，负数按 0 处理
     * @throws JellyfishException 摘要或边界消息标识为空白时抛出
     */
    public SessionCompaction(String summary, String boundaryMessageId, long createdAt,
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
        return "SessionCompaction{boundaryMessageId=" + boundaryMessageId
                + ", summaryLength=" + summary.length()
                + ", droppedMessageCount=" + droppedMessageCount + '}';
    }
}
