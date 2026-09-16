package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

/**
 * 会话压缩事件：{@code /compact} 把旧历史压成摘要并推进边界后广播。
 * <p>
 * <b>为什么要有这条事件</b>：压缩改变了「模型能看到什么」，而屏幕上的会话一条没少——两者的差异
 * 必须有一个可观测的出口，否则「模型为什么忘了刚才说的话」会变成一个查不出来的谜。
 * <b>但外壳不靠它刷新界面</b>：压缩由 {@code /compact} 触发、跑在专用线程上，
 * 而 TUI 每帧本来就要读压缩状态（要显示「压缩中…」），因此结果提示以轮询状态机为准；
 * 本事件是给指标、审计与将来的多端同步用的。
 * <p>
 * <b>不携带摘要正文</b>：这是异步、可丢弃的 best-effort 通道，摘要可能上千字，塞进去既放大体积
 * 又扩大隐私面；订阅者用 {@code sessionId} 回查会话即可。会话落盘走的是同步扩展点，不依赖本事件。
 * <p>
 * <b>携带「被丢弃条数」</b>：一次摘要请求受同一个上下文窗口约束，装不下的最旧那一段会被直接丢弃
 * （既不在摘要里、也不再进请求）。这是一个不可逆的信息损失，订阅者有权知道——记忆类插件正是据此
 * 决定要不要把那段历史另存一份。
 *
 * @author zcd
 */
public final class CompactionAppliedEvent extends AbstractJellyfishEvent {

    /** 摘要覆盖到的最后一条消息标识。 */
    private final String boundaryMessageId;

    /** 本次被摘要覆盖的消息条数。 */
    private final int compressedCount;

    /** 本次因超出摘要输入预算而被直接丢弃的消息条数。 */
    private final int droppedCount;

    /** 摘要正文字符数。 */
    private final int summaryLength;

    /**
     * 构造压缩事件。
     *
     * @param sessionId         会话标识
     * @param boundaryMessageId 摘要覆盖到的最后一条消息标识
     * @param compressedCount   本次被摘要覆盖的消息条数
     * @param droppedCount      本次被直接丢弃的消息条数
     * @param summaryLength     摘要正文字符数
     */
    public CompactionAppliedEvent(String sessionId, String boundaryMessageId, int compressedCount,
                                  int droppedCount, int summaryLength) {
        super(sessionId);
        this.boundaryMessageId = boundaryMessageId;
        this.compressedCount = compressedCount;
        this.droppedCount = droppedCount;
        this.summaryLength = summaryLength;
    }

    /**
     * 获取边界消息标识。
     *
     * @return 边界消息标识
     */
    public String getBoundaryMessageId() {
        return boundaryMessageId;
    }

    /**
     * 获取本次被覆盖的消息条数。
     *
     * @return 消息条数
     */
    public int getCompressedCount() {
        return compressedCount;
    }

    /**
     * 获取本次被直接丢弃的消息条数。
     *
     * @return 消息条数，非负
     */
    public int getDroppedCount() {
        return droppedCount;
    }

    /**
     * 获取摘要正文字符数。
     *
     * @return 字符数
     */
    public int getSummaryLength() {
        return summaryLength;
    }
}
