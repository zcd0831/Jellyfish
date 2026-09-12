package zcd.jellyfish.infra.llm;

/**
 * 一次 LLM 调用的 token 使用量。
 * <p>
 * 部分厂商不返回该信息，此时调用方拿到的是 {@code null}；本类内部若 {@code totalTokens} 未提供
 * （小于等于 0），会用 {@code promptTokens + completionTokens} 兜底。
 *
 * @author zcd
 */
public final class LlmUsage {

    /** 输入（提示词）token 数。 */
    private final int promptTokens;

    /** 输出（补全）token 数。 */
    private final int completionTokens;

    /** 总 token 数，厂商未提供时由输入与输出相加得到。 */
    private final int totalTokens;

    /**
     * 构造 token 使用量。
     *
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数，小于等于 0 时自动按输入 + 输出计算
     */
    public LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
    }

    /**
     * 获取输入 token 数。
     *
     * @return 输入 token 数
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
                '}';
    }
}
