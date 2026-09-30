package zcd.jellyfish.api.extension;

/**
 * 会话级累计 token 用量快照。
 * <p>
 * 与 {@link TokenUsageSnapshot} 的分工：那个是「一次调用」，本类是「整个会话累计」。
 * 字段用 {@code long}：会话可以很长，累计值用 {@code int} 迟早溢出。
 * <p>
 * <b>缓存计数是累计值</b>：命中率由「累计命中 / 累计输入」现算，而不是把每次调用的命中率取平均——
 * 后者会让一次 3 token 的调用与一次 100000 token 的调用等权。
 * <p>
 * <b>输入的 {@code promptTokens} 是「总输入」</b>（含缓存命中与建缓存的部分），两个缓存字段是它的子集。
 * 各厂商原本的口径并不一致（Anthropic 的三个输入字段互斥），归一化在解析处完成，
 * 因此消费方只需要一套公式。
 *
 * @author zcd
 */
public final class SessionUsageSnapshot {

    /** 累计输入 token 总数（含缓存命中与建缓存的部分）。 */
    private final long promptTokens;

    /** 累计输出 token 数。 */
    private final long completionTokens;

    /** 累计总 token 数。 */
    private final long totalTokens;

    /** 累计模型调用次数（含厂商未返回用量的调用）。 */
    private final long llmCalls;

    /** 累计命中缓存的输入 token 数；旧会话文件缺这个字段时按 0 处理。 */
    private final long cacheReadTokens;

    /** 累计写入缓存的输入 token 数；旧会话文件缺这个字段时按 0 处理。 */
    private final long cacheWriteTokens;

    /**
     * 构造会话累计用量快照。
     *
     * @param promptTokens     累计输入 token 总数（含缓存命中与建缓存的部分）
     * @param completionTokens 累计输出 token 数
     * @param totalTokens      累计总 token 数
     * @param llmCalls         累计模型调用次数
     * @param cacheReadTokens  累计命中缓存的输入 token 数
     * @param cacheWriteTokens 累计写入缓存的输入 token 数
     */
    public SessionUsageSnapshot(long promptTokens, long completionTokens, long totalTokens, long llmCalls,
                                long cacheReadTokens, long cacheWriteTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.llmCalls = llmCalls;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    /**
     * 获取累计输入 token 数。
     *
     * @return 累计输入 token 总数，含缓存命中与建缓存的部分
     */
    public long getPromptTokens() {
        return promptTokens;
    }

    /**
     * 获取累计输出 token 数。
     *
     * @return 累计输出 token 数
     */
    public long getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 获取累计总 token 数。
     *
     * @return 累计总 token 数
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取累计模型调用次数。
     *
     * @return 累计模型调用次数
     */
    public long getLlmCalls() {
        return llmCalls;
    }

    /**
     * 获取累计命中缓存的输入 token 数。
     *
     * @return 累计命中缓存的输入 token 数
     */
    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 获取累计写入缓存的输入 token 数。
     *
     * @return 累计写入缓存的输入 token 数
     */
    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    @Override
    public String toString() {
        return "SessionUsageSnapshot{prompt=" + promptTokens + ", completion=" + completionTokens
                + ", total=" + totalTokens + ", calls=" + llmCalls
                + ", cacheRead=" + cacheReadTokens + ", cacheWrite=" + cacheWriteTokens + '}';
    }
}
