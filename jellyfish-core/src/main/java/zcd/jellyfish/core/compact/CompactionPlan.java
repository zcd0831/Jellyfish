package zcd.jellyfish.core.compact;

import zcd.jellyfish.infra.llm.LlmRequest;

/**
 * 一次压缩的执行计划：压哪一段、发什么给模型、边界落在哪里。
 * <p>
 * <b>为什么先出计划再执行</b>：{@code /compact preview} 与真正的压缩必须是同一套判断——预览说
 * 「将压缩 42 条」，执行就必须真的压那 42 条。因此把「选段 + 渲染 + 组请求」做成一个纯步骤（{@link
 * ConversationCompactor#plan}），预览只读它、压缩拿它去发。若预览自己算一遍、执行再算一遍，
 * 中间插入一条消息就会让两者对不上，而用户已经照着预览做了决定。
 * <p>
 * <b>一次压完，装不下就丢最旧的</b>：待压范围整段进摘要即为理想情形；一旦它超过摘要输入预算，
 * 就从最旧侧丢弃到装得下为止（只压最新的一段），不留到下一次——单次命令单次调用，花的钱与耗时都能预计。
 * <p>
 * <b>计划里的正文是物化的字符串</b>：执行发生在另一个线程，那时会话可能又多了一条消息；
 * 物化之后计划描述的就是「按下回车那一刻的历史」，边界以 {@code messageId} 记账（消息只增不删，
 * 因此标识永远有效，而下标会漂）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CompactionPlan {

    /** 摘要请求，已按模型上下文窗口截好输入。 */
    private final LlmRequest request;

    /** 摘要覆盖到的最后一条消息标识：它及其之前的消息不再进请求。 */
    private final String boundaryMessageId;

    /** 本次将被摘要覆盖的消息条数。 */
    private final int compressedCount;

    /**
     * 本次被直接丢弃的消息条数。
     * <p>
     * 单次压缩口径下，待压范围装不进一次摘要请求时只能从最旧侧丢弃——被丢弃的那一段既不进摘要、
     * 也不再进请求，是真正消失的数据。这里如实记下来，是为了让它出现在完成提示与 system prompt 里，
     * 而不是悄悄消失。
     */
    private final int droppedCount;

    /** 压完之后仍会随请求发出的消息条数。 */
    private final int keepCount;

    /**
     * 摘要输入里本次要按<b>未命中价</b>计费的 token 估算值。
     * <p>
     * <b>不是「输入的规模」</b>：走 cache-safe fork 时输入可能很长，但其中绝大部分与父请求逐字节
     * 相同、按命中价计费，真正新增的只有末尾那条指令。反馈给用户的就是这个新增量。
     */
    private final int estimatedTokens;

    /**
     * 本次是否走的是 cache-safe fork。
     * <p>
     * 两条路径的差别是数量级的（命中价与未命中价差约十倍），用户有权知道自己在花哪一种钱。
     */
    private final boolean forked;

    /**
     * 构造压缩计划。
     *
     * @param request           摘要请求，不可为 {@code null}
     * @param boundaryMessageId 边界消息标识，不可为空白
     * @param compressedCount   本次覆盖的消息条数，保证为正
     * @param droppedCount      本次被直接丢弃的消息条数，非负
     * @param keepCount         压完后保留的原文条数
     * @param estimatedTokens   本次要按未命中价计费的 token 估算值
     * @param forked            是否走 cache-safe fork
     */
    CompactionPlan(LlmRequest request, String boundaryMessageId, int compressedCount, int droppedCount,
                   int keepCount, int estimatedTokens, boolean forked) {
        this.request = request;
        this.boundaryMessageId = boundaryMessageId;
        this.compressedCount = compressedCount;
        this.droppedCount = droppedCount;
        this.keepCount = keepCount;
        this.estimatedTokens = estimatedTokens;
        this.forked = forked;
    }

    /**
     * 获取摘要请求。
     *
     * @return 摘要请求
     */
    public LlmRequest getRequest() {
        return request;
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
     * 获取本次覆盖的消息条数。
     *
     * @return 消息条数
     */
    public int getCompressedCount() {
        return compressedCount;
    }

    /**
     * 获取本次被直接丢弃的消息条数。
     *
     * @return 条数，非负；{@code 0} 表示待压范围整段都进了摘要
     */
    public int getDroppedCount() {
        return droppedCount;
    }

    /**
     * 获取压完后保留的原文条数。
     *
     * @return 条数
     */
    public int getKeepCount() {
        return keepCount;
    }

    /**
     * 获取本次要按未命中价计费的 token 估算值。
     *
     * @return 估算 token 数
     */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /**
     * 判断本次是否走的是 cache-safe fork。
     *
     * @return 复用父请求前缀返回 {@code true}；回退到「渲染正文」路径返回 {@code false}
     */
    public boolean isForked() {
        return forked;
    }

    /**
     * 判断本次是否丢弃了旧消息。
     *
     * @return 有消息被直接丢弃返回 {@code true}
     */
    public boolean hasDropped() {
        return droppedCount > 0;
    }
}
