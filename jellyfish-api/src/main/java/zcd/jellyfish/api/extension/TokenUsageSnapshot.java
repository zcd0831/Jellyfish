package zcd.jellyfish.api.extension;

/**
 * 单次模型调用的 token 用量快照。
 * <p>
 * 对应内核里的「一次调用用量」：所有字段可能为 {@code null}——厂商没返回用量时是「未知」，
 * 不是「用了 0」。
 * <p>
 * <b>为什么不与 {@link SessionUsageSnapshot} 合并</b>：会话累计用量还多一个「调用次数」，
 * 而单次用量没有这个维度；两者语义不同，合并只能靠「某个字段在这里没意义」的注释来兜底。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * <b>输入的 {@code promptTokens} 是「总输入」</b>（含缓存命中与建缓存的部分），两个缓存字段是它的子集。
 * 各厂商原本的口径并不一致（Anthropic 的三个输入字段互斥），归一化在解析处完成，
 * 因此消费方只需要一套公式。
 *
 * @author zcd
 */
public final class TokenUsageSnapshot {

    /** 输入 token 总数（含缓存命中与建缓存的部分），未知时为 {@code null}。 */
    private final Integer promptTokens;

    /** 输出 token 数，未知时为 {@code null}。 */
    private final Integer completionTokens;

    /** 总 token 数，未知时为 {@code null}。 */
    private final Integer totalTokens;

    /** 命中缓存的输入 token 数，未知时为 {@code null}。 */
    private final Integer cacheReadTokens;

    /** 写入缓存的输入 token 数，未知时为 {@code null}。 */
    private final Integer cacheWriteTokens;

    /**
     * 构造单次调用用量快照。
     *
     * @param promptTokens     输入 token 总数，可为 {@code null}
     * @param completionTokens 输出 token 数，可为 {@code null}
     * @param totalTokens      总 token 数，可为 {@code null}
     * @param cacheReadTokens  命中缓存的输入 token 数，可为 {@code null}
     * @param cacheWriteTokens 写入缓存的输入 token 数，可为 {@code null}
     */
    public TokenUsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                              Integer cacheReadTokens, Integer cacheWriteTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    /**
     * 获取输入 token 数。
     *
     * @return 输入 token 总数，含缓存命中与建缓存的部分；未知时为 {@code null}
     */
    public Integer getPromptTokens() {
        return promptTokens;
    }

    /**
     * 获取命中缓存的输入 token 数。
     *
     * @return 命中缓存的输入 token 数，未知时为 {@code null}
     */
    public Integer getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 获取写入缓存的输入 token 数。
     *
     * @return 写入缓存的输入 token 数，未知时为 {@code null}
     */
    public Integer getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 获取输出 token 数。
     *
     * @return 输出 token 数，未知时为 {@code null}
     */
    public Integer getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 获取总 token 数。
     *
     * @return 总 token 数，未知时为 {@code null}
     */
    public Integer getTotalTokens() {
        return totalTokens;
    }

    @Override
    public String toString() {
        return "TokenUsageSnapshot{prompt=" + promptTokens + ", completion=" + completionTokens
                + ", total=" + totalTokens + ", cacheRead=" + cacheReadTokens
                + ", cacheWrite=" + cacheWriteTokens + '}';
    }
}
