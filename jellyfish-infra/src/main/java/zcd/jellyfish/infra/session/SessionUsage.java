package zcd.jellyfish.infra.session;

import zcd.jellyfish.infra.llm.LlmUsage;

/**
 * 会话级 token 累计快照。不可变值对象，每次累加返回新实例。
 * <p>
 * 为什么不直接复用 {@link LlmUsage}：后者表达「<b>一次</b>调用的用量」且字段是 {@code int}，
 * 既没有累加语义、也会在长会话里溢出。本类用 {@code long} 承载累计值，并额外记录调用次数
 * （{@code llmCalls}），让「这个会话花了几次调用、多少 token」成为 O(1) 可读的会话属性，
 * 而不必遍历消息列表。
 *
 * @author zcd
 */
public final class SessionUsage {

    /** 零用量的初始快照。 */
    public static final SessionUsage EMPTY = new SessionUsage(0L, 0L, 0L, 0L);

    /** 累计输入 token 数。 */
    private final long promptTokens;

    /** 累计输出 token 数。 */
    private final long completionTokens;

    /** 累计总 token 数。 */
    private final long totalTokens;

    /** 累计 LLM 调用次数（含未返回用量的调用）。 */
    private final long llmCalls;

    /**
     * 构造用量快照。
     *
     * @param promptTokens     累计输入 token 数
     * @param completionTokens 累计输出 token 数
     * @param totalTokens      累计总 token 数
     * @param llmCalls         累计调用次数
     */
    public SessionUsage(long promptTokens, long completionTokens, long totalTokens, long llmCalls) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.llmCalls = llmCalls;
    }

    /**
     * 累加一次调用的用量。
     * <p>
     * {@code usage} 为 {@code null} 时（厂商未返回用量）只累加调用次数，token 三个字段保持不变：
     * 「未返回」不等于「用了 0 token」，计数仍然要涨，否则调用次数会漏。
     *
     * @param usage 一次调用的 token 用量，可为 {@code null}
     * @return 累加后的新快照
     */
    public SessionUsage plus(LlmUsage usage) {
        if (usage == null) {
            return new SessionUsage(promptTokens, completionTokens, totalTokens, llmCalls + 1L);
        }
        return new SessionUsage(promptTokens + usage.getPromptTokens(),
                completionTokens + usage.getCompletionTokens(),
                totalTokens + usage.getTotalTokens(),
                llmCalls + 1L);
    }

    /**
     * 累加另一份累计快照（含调用次数）。
     * <p>
     * <b>为什么需要它而不是把总量包成一个 {@link LlmUsage} 再调 {@link #plus(LlmUsage)}</b>：
     * 后者恒定只加一次调用。子代理的一个回合可能调了三次模型，用那个入口归集会得到
     * 「token 对、调用次数少两次」的账，而调用次数正是判断「一次任务到底花了多少来回」的依据。
     * <p>
     * 子代理的用量并入父会话时走这条路径：对它而言那些调用确实发生了，也该算在这个对话头上。
     *
     * @param other 另一份累计快照，可为 {@code null}（按无变化处理）
     * @return 累加后的新快照
     */
    public SessionUsage plus(SessionUsage other) {
        if (other == null || other == EMPTY) {
            return this;
        }
        return new SessionUsage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                llmCalls + other.llmCalls);
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
     * 获取累计 LLM 调用次数。
     *
     * @return 累计调用次数
     */
    public long getLlmCalls() {
        return llmCalls;
    }

    /**
     * 返回用量快照的可读表示，便于日志排查。
     *
     * @return 描述字符串
     */
    @Override
    public String toString() {
        return "SessionUsage{" +
                "promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", llmCalls=" + llmCalls +
                '}';
    }
}
