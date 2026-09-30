package zcd.jellyfish.api.llm;

/**
 * 传输层看到的一次调用的 token 用量。
 * <p>
 * <b>输入口径是「总输入」</b>，与内核 {@code LlmUsage} 一致：缓存命中与建缓存的部分是它的<b>子集</b>。
 * 三家厂商的原始记账方式并不一致（Anthropic 的三个输入字段互斥，OpenAI 系的 {@code prompt_tokens}
 * 已含缓存），归一化是<b>插件在解析响应时</b>的责任——内核不替插件猜，因为内核看不懂原始响应。
 * <p>
 * <b>厂商不返回用量时不要构造本对象</b>：用 {@code null} 表达「没有这份信息」，与「用量为零」
 * 是两回事（前者不该进成本账，后者要记一笔 0）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportUsage {

    /** 输入 token 总数，含缓存命中与建缓存的部分。 */
    private final int promptTokens;

    /** 输出 token 数。 */
    private final int completionTokens;

    /** 总 token 数，小于等于 0 时按输入 + 输出计算。 */
    private final int totalTokens;

    /** 输入中命中缓存的部分。 */
    private final int cacheReadTokens;

    /** 输入中写入缓存的部分。 */
    private final int cacheWriteTokens;

    /**
     * 构造不带缓存信息的用量。
     *
     * @param promptTokens     输入 token 总数
     * @param completionTokens 输出 token 数
     */
    public LlmTransportUsage(int promptTokens, int completionTokens) {
        this(promptTokens, completionTokens, 0, 0, 0);
    }

    /**
     * 构造用量。
     *
     * @param promptTokens     输入 token <b>总数</b>（含缓存命中与建缓存的部分）
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数，小于等于 0 时按输入 + 输出计算
     * @param cacheReadTokens  输入中命中缓存的部分，负数按 0 处理
     * @param cacheWriteTokens 输入中写入缓存的部分，负数按 0 处理
     */
    public LlmTransportUsage(int promptTokens, int completionTokens, int totalTokens,
                             int cacheReadTokens, int cacheWriteTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
        this.cacheReadTokens = Math.max(0, cacheReadTokens);
        this.cacheWriteTokens = Math.max(0, cacheWriteTokens);
    }

    /**
     * 获取输入 token 总数。
     *
     * @return 输入 token 总数
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
     * @return 命中缓存的输入 token 数
     */
    public int getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 获取输入中写入缓存的部分。
     *
     * @return 写入缓存的输入 token 数
     */
    public int getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    @Override
    public String toString() {
        return "LlmTransportUsage{promptTokens=" + promptTokens + ", completionTokens=" + completionTokens
                + ", totalTokens=" + totalTokens + ", cacheReadTokens=" + cacheReadTokens
                + ", cacheWriteTokens=" + cacheWriteTokens + '}';
    }
}
