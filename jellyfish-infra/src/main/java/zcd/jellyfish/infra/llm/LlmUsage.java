package zcd.jellyfish.infra.llm;

/**
 * 一次 LLM 调用的 token 使用量。
 * <p>
 * 部分厂商不返回该信息，此时调用方拿到的是 {@code null}；本类内部若 {@code totalTokens} 未提供
 * （小于等于 0），会用 {@code promptTokens + completionTokens} 兜底。
 * <p>
 * <b>输入口径是「总输入」，厂商差异在解析处归一化</b>。三家对缓存 token 的记账方式并不一致：
 * <ul>
 *     <li><b>OpenAI / DeepSeek</b>：{@code prompt_tokens} <b>已经包含</b>缓存命中部分
 *     （DeepSeek 官方文档原话：{@code prompt_tokens} “equals prompt_cache_hit_tokens +
 *     prompt_cache_miss_tokens”）；</li>
 *     <li><b>Gemini</b>：{@code promptTokenCount} 是总输入，{@code cachedContentTokenCount} 是它的子集；</li>
 *     <li><b>Anthropic</b>：三个输入字段是 prompt 的<b>互斥划分</b>——{@code input_tokens} 只计
 *     「新增、未命中、未建缓存」的那部分。因此这里的 {@code promptTokens} 与缓存字段<b>不相交</b>，
 *     是真正常见的例外。</li>
 * </ul>
 * 各客户端在解析时统一归一化成「{@link #promptTokens} = <b>总输入</b>」，
 * {@link #cacheReadTokens} / {@link #cacheWriteTokens} 恒为它的<b>子集</b>。这样命中率在任何厂商上
 * 都是同一个公式，厂商差异只留在解析那一处。
 * <p>
 * <b>为什么值得归一化</b>：不归一化，「命中率的分母」就变成按厂商而异的东西，于是每个消费方
 * （{@code /usage}、指标、插件）都得先知道自己在跟谁说话——而它们都不该知道这件事。
 * 归一化的代价是 Anthropic 的输入总数会变大：它此前把缓存部分<b>整个漏掉了</b>，
 * 那是一处少算，不是这里多算。
 * <p>
 * <b>缓存字段缺省为 0</b>：厂商未上报时与「上报了 0」不可区分。当前接入的四家在有缓存活动时都会
 * 带上这些字段，因此 0 的读法就是「没有命中」——但这层含义由消费方自己决定，本类不替它猜。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmUsage {

    /** 输入（提示词）token 总数，含缓存命中与建缓存的部分。 */
    private final int promptTokens;

    /** 输出（补全）token 数。 */
    private final int completionTokens;

    /** 总 token 数，厂商未提供时由输入与输出相加得到。 */
    private final int totalTokens;

    /** 输入中命中缓存的部分，厂商未上报时为 0。 */
    private final int cacheReadTokens;

    /** 输入中写入缓存的部分，厂商未上报时为 0（只有显式缓存的厂商会非 0）。 */
    private final int cacheWriteTokens;

    /**
     * 构造不带缓存信息的用量。
     * <p>
     * 供「这次调用与缓存无关」的路径使用（例如测试、或没有缓存概念的厂商）。走这条路的用量，
     * 命中率恒为 0——它表达的是「没有这份信息」，而不是「查询过缓存但没命中」。
     *
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数，小于等于 0 时自动按输入 + 输出计算
     */
    public LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0, 0);
    }

    /**
     * 构造用量。
     *
     * @param promptTokens     输入 token <b>总数</b>（含缓存命中与建缓存的部分）
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数，小于等于 0 时自动按输入 + 输出计算
     * @param cacheReadTokens  输入中命中缓存的部分，负数按 0 处理
     * @param cacheWriteTokens 输入中写入缓存的部分，负数按 0 处理
     */
    public LlmUsage(int promptTokens, int completionTokens, int totalTokens, int cacheReadTokens,
                    int cacheWriteTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
        this.cacheReadTokens = Math.max(0, cacheReadTokens);
        this.cacheWriteTokens = Math.max(0, cacheWriteTokens);
    }

    /**
     * 获取输入 token 总数。
     *
     * @return 输入 token 总数，含缓存命中与建缓存的部分
     */
    public int getPromptTokens() {
        return promptTokens;
    }

    /**
     * 获取输出 token 数。
     *
     * @return 输出 token 数
     */
    public int getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 获取总 token 数。
     *
     * @return 总 token 数
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * 获取输入中命中缓存的部分。
     *
     * @return 命中缓存的输入 token 数，厂商未上报时为 0
     */
    public int getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 获取输入中写入缓存的部分。
     *
     * @return 写入缓存的输入 token 数，厂商未上报时为 0
     */
    public int getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 获取本次调用的缓存命中率：命中缓存的输入占全部输入的比例。
     * <p>
     * 分母是<b>总输入</b>（{@link #promptTokens}），因此在任何厂商上都是同一个公式——
     * 这正是解析处要做归一化的理由。
     *
     * @return 命中率，落在 {@code [0,1]}；输入为 0 时返回 0
     */
    public double getCacheHitRate() {
        return promptTokens <= 0 ? 0.0d : (double) cacheReadTokens / (double) promptTokens;
    }

    /**
     * 返回 token 使用量的可读表示，便于日志排查。
     *
     * @return 描述字符串
     */
    @Override
    public String toString() {
        return "LlmUsage{" +
                "promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", cacheReadTokens=" + cacheReadTokens +
                ", cacheWriteTokens=" + cacheWriteTokens +
                '}';
    }
}
