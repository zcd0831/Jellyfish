package zcd.jellyfish.infra.session;

import zcd.jellyfish.infra.llm.LlmUsage;

/**
 * 会话级 token 累计快照。不可变值对象，每次累加返回新实例。
 * <p>
 * 为什么不直接复用 {@link LlmUsage}：后者表达「<b>一次</b>调用的用量」且字段是 {@code int}，
 * 既没有累加语义、也会在长会话里溢出。本类用 {@code long} 承载累计值，并额外记录调用次数
 * （{@code llmCalls}），让「这个会话花了几次调用、多少 token」成为 O(1) 可读的会话属性，
 * 而不必遍历消息列表。
 * <p>
 * <b>缓存计数是累计值，命中率由两个累计量现算</b>：{@link #getCacheHitRate()} 用「累计命中 / 累计输入」
 * 而不是「每次命中率的平均」——前者才是这个会话真实的缓存利用率，后者会被短调用（分母小）带偏。
 * 输入的口径（总输入、缓存部分是其子集）与厂商差异的归一化见 {@link LlmUsage}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionUsage {

    /** 零用量的初始快照。 */
    public static final SessionUsage EMPTY = new SessionUsage(0L, 0L, 0L, 0L);

    /** 累计输入 token 总数（含缓存命中与建缓存的部分）。 */
    private final long promptTokens;

    /** 累计输出 token 数。 */
    private final long completionTokens;

    /** 累计总 token 数。 */
    private final long totalTokens;

    /** 累计 LLM 调用次数（含未返回用量的调用）。 */
    private final long llmCalls;

    /** 累计命中缓存的输入 token 数。 */
    private final long cacheReadTokens;

    /** 累计写入缓存的输入 token 数。 */
    private final long cacheWriteTokens;

    /**
     * 构造用量快照。
     *
     * @param promptTokens     累计输入 token 数
     * @param completionTokens 累计输出 token 数
     * @param totalTokens      累计总 token 数
     * @param llmCalls         累计调用次数
     */
    public SessionUsage(long promptTokens, long completionTokens, long totalTokens, long llmCalls) {
        this(promptTokens, completionTokens, totalTokens, llmCalls, 0L, 0L);
    }

    /**
     * 构造用量快照。
     *
     * @param promptTokens     累计输入 token 总数（含缓存命中与建缓存的部分）
     * @param completionTokens 累计输出 token 数
     * @param totalTokens      累计总 token 数
     * @param llmCalls         累计调用次数
     * @param cacheReadTokens  累计命中缓存的输入 token 数
     * @param cacheWriteTokens 累计写入缓存的输入 token 数
     */
    public SessionUsage(long promptTokens, long completionTokens, long totalTokens, long llmCalls,
                        long cacheReadTokens, long cacheWriteTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.llmCalls = llmCalls;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
    }

    /**
     * 累加一次调用的用量。
     * <p>
     * {@code usage} 为 {@code null} 时（厂商未返回用量）只累加调用次数，token 字段保持不变：
     * 「未返回」不等于「用了 0 token」，计数仍然要涨，否则调用次数会漏。
     *
     * @param usage 一次调用的 token 用量，可为 {@code null}
     * @return 累加后的新快照
     */
    public SessionUsage plus(LlmUsage usage) {
        if (usage == null) {
            return new SessionUsage(promptTokens, completionTokens, totalTokens, llmCalls + 1L,
                    cacheReadTokens, cacheWriteTokens);
        }
        return new SessionUsage(promptTokens + usage.getPromptTokens(),
                completionTokens + usage.getCompletionTokens(),
                totalTokens + usage.getTotalTokens(),
                llmCalls + 1L,
                cacheReadTokens + usage.getCacheReadTokens(),
                cacheWriteTokens + usage.getCacheWriteTokens());
    }

    /**
     * 只累加 token，<b>不计</b>调用次数。
     * <p>
     * 给「这条消息不是模型响应」的路径用：{@code llmCalls} 记的是「调过几次模型」，
     * 而 user 输入与 tool 结果是本地产物——它们只是被追加进会话，并没有换来一次模型调用。
     * 若把它们也走 {@link #plus(LlmUsage)}，该路径的 {@code null} 会按「未返回用量的调用」计一次，
     * 于是调用次数涨成消息条数。
     * <p>
     * {@code usage} 为 {@code null} 时返回本实例：没有用量可加，也没有调用可计。
     * 非 {@code null} 时累加 token 字段但保持调用次数不变——那份额度确实花掉了，只是不来自新增的调用。
     *
     * @param usage 一条消息承载的 token 用量，可为 {@code null}
     * @return 累加后的新快照
     */
    public SessionUsage plusTokens(LlmUsage usage) {
        if (usage == null) {
            return this;
        }
        return new SessionUsage(promptTokens + usage.getPromptTokens(),
                completionTokens + usage.getCompletionTokens(),
                totalTokens + usage.getTotalTokens(), llmCalls,
                cacheReadTokens + usage.getCacheReadTokens(),
                cacheWriteTokens + usage.getCacheWriteTokens());
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
                llmCalls + other.llmCalls,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens);
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
     * 获取累计 LLM 调用次数。
     *
     * @return 累计调用次数
     */
    public long getLlmCalls() {
        return llmCalls;
    }

    /**
     * 获取累计命中缓存的输入 token 数。
     *
     * @return 累计命中缓存的输入 token 数，厂商不上报时为 0
     */
    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * 获取累计写入缓存的输入 token 数。
     *
     * @return 累计写入缓存的输入 token 数，厂商不上报时为 0
     */
    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 获取本会话的缓存命中率：累计命中除以累计输入。
     * <p>
     * 用两个累计量相除，而不是把每次调用的命中率取平均——后者会让一次 3 token 的调用与一次
     * 100000 token 的调用等权，算出来的数不代表这个会话真实的缓存利用率。
     *
     * @return 命中率，落在 {@code [0,1]}；累计输入为 0 时返回 0
     */
    public double getCacheHitRate() {
        return promptTokens <= 0L ? 0.0d : (double) cacheReadTokens / (double) promptTokens;
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
                ", cacheReadTokens=" + cacheReadTokens +
                ", cacheWriteTokens=" + cacheWriteTokens +
                '}';
    }
}
