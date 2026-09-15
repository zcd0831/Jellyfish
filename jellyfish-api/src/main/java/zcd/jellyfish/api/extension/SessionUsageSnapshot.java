package zcd.jellyfish.api.extension;

/**
 * 会话级累计 token 用量快照。
 * <p>
 * 与 {@link TokenUsageSnapshot} 的分工：那个是「一次调用」，本类是「整个会话累计」。
 * 字段用 {@code long}：会话可以很长，累计值用 {@code int} 迟早溢出。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionUsageSnapshot {

    /** 累计输入 token 数。 */
    private final long promptTokens;

    /** 累计输出 token 数。 */
    private final long completionTokens;

    /** 累计总 token 数。 */
    private final long totalTokens;

    /** 累计模型调用次数（含厂商未返回用量的调用）。 */
    private final long llmCalls;

    /**
     * 构造会话累计用量快照。
     *
     * @param promptTokens     累计输入 token 数
     * @param completionTokens 累计输出 token 数
     * @param totalTokens      累计总 token 数
     * @param llmCalls         累计模型调用次数
     */
    public SessionUsageSnapshot(long promptTokens, long completionTokens, long totalTokens, long llmCalls) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.llmCalls = llmCalls;
    }

    /**
     * 获取累计输入 token 数。
     *
     * @return 累计输入 token 数
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

    @Override
    public String toString() {
        return "SessionUsageSnapshot{prompt=" + promptTokens + ", completion=" + completionTokens
                + ", total=" + totalTokens + ", calls=" + llmCalls + '}';
    }
}
