package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code react} 段：ReAct 循环的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾），只承载单份文件的内容。
 * 六项直接参数分别约束「一个回合最多几轮」「上下文预算给系统提示词留多少」「单个工具输出截多长」
 * 「上下文用到多少就自动压缩」与压缩的两项（保留多少条原文、摘要最多多长）——后面几项归这里
 * 而不是新开一段，是因为它们与前三项同属「ReAct 运行期的一次请求长什么样」，放在一处才看得出
 * 它们互相牵制（预留越多、可压的越少；阈值越低、自动压缩越频繁）。
 * 它们都属于「用户可能想调、但内核必须有安全缺省」的量，因此缺省值写在类里而不是配置里。
 * 工具结果的落盘与上下文治理另成一段（{@code toolOutput}），因为它只服务「一次工具结果太长时怎么办」
 * 这一件事，与上面几项的用途不同。
 * <p>
 * 非法值（轮数与输出长度为非正数、预留为负数）一律回退到缺省值而不是报错：配置问题不阻断启动
 * 是本仓库的既有口径，真正的轮次约束与裁剪在运行期由 {@code ReActLooper} 与 {@code ContextWindow}
 * 兜底。预留 token 数与自动压缩百分比允许显式配 {@code 0}，因此缺省判定以 {@code null} 区分
 * 「未配置」与「配了 0」。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class ReactSettings {

    /** 单个回合的最大循环轮数：模型连续请求工具时的兜底上限。 */
    public static final int DEFAULT_MAX_ROUNDS = 16;

    /** 上下文预算里为系统提示词、待办注入与估算误差预留的 token 数。 */
    public static final int DEFAULT_CONTEXT_RESERVE_TOKENS = 1024;

    /** 单个工具输出写入会话与回灌模型前允许的最大字符数。 */
    public static final int DEFAULT_MAX_TOOL_OUTPUT_CHARS = 20000;

    /**
     * {@code /compact} 默认保留的最近消息条数。
     * <p>
     * 缺省不把历史压干：最后几条消息里通常有用户正在追问的那件事，全压进摘要会让模型立刻失忆一次。
     */
    public static final int DEFAULT_COMPACT_KEEP_RECENT_MESSAGES = 20;

    /** 压缩摘要的长度上限（字符数）：超出按本地截断处理，避免摘要本身又变成一份长上下文。 */
    public static final int DEFAULT_COMPACT_MAX_SUMMARY_CHARS = 4000;

    /**
     * 上下文用到多少百分比就自动压缩一次。
     * <p>
     * 默认为 80 而不是 100 是有意的：{@code ContextWindow} 在 100% 时会从最旧侧<b>静默丢弃</b>历史，
     * 而那正是信息真正丢失的时刻。在 80% 时先压一次，丢掉的是「一段旧对话的细节」，
     * 换回来的是「对话能继续下去」。
     */
    public static final int DEFAULT_AUTO_COMPACT_PERCENT = 80;

    /** 插件策略能给的摘要长度上限下限：再小就压不住任何东西了。 */
    public static final int MIN_COMPACT_MAX_SUMMARY_CHARS = 200;

    /** 插件策略能给的摘要长度上限上限：再大摘要本身就成了一轮长上下文。 */
    public static final int MAX_COMPACT_MAX_SUMMARY_CHARS = 20000;

    /** 最大循环轮数。 */
    private final int maxRounds;

    /** 上下文预算预留 token 数。 */
    private final int contextReserveTokens;

    /** 单个工具输出最大字符数。 */
    private final int maxToolOutputChars;

    /** {@code /compact} 默认保留的最近消息条数。 */
    private final int compactKeepRecentMessages;

    /** 压缩摘要长度上限（字符数）。 */
    private final int compactMaxSummaryChars;

    /** 上下文用到多少百分比就自动压缩（{@code 0} 表示关闭）。 */
    private final int autoCompactPercent;

    /** 工具结果落盘与上下文治理参数。 */
    private final ToolOutputSettings toolOutput;

    /** 提示词缓存的治理参数。 */
    private final ReactCacheSettings cache;

    /**
     * 构造缺省运行期参数。
     */
    public ReactSettings() {
        this(null, null, null, null, null, null, null, null);
    }

    /**
     * 兼容旧调用点的便捷构造器：工具结果段按缺省值处理。
     * <p>
     * 保留它是因为绝大多数调用点（测试与运行期）只关心前六项；让它们被迫多写一个 {@code null}
     * 只会把 {@code ReactSettings} 的构造噪音扩散到整个仓库。
     *
     * @param maxRounds                 最大循环轮数
     * @param contextReserveTokens      上下文预留 token 数
     * @param maxToolOutputChars        单个工具输出最大字符数
     * @param compactKeepRecentMessages {@code /compact} 保留的最近消息条数
     * @param compactMaxSummaryChars    摘要长度上限
     * @param autoCompactPercent        自动压缩的触发百分比
     */
    public ReactSettings(Integer maxRounds, Integer contextReserveTokens, Integer maxToolOutputChars,
                         Integer compactKeepRecentMessages, Integer compactMaxSummaryChars,
                         Integer autoCompactPercent) {
        this(maxRounds, contextReserveTokens, maxToolOutputChars, compactKeepRecentMessages,
                compactMaxSummaryChars, autoCompactPercent, null, null);
    }

    /**
     * 兼容旧调用点的便捷构造器：缓存段按缺省值处理。
     * <p>
     * 与六参构造器同一条理由：绝大多数调用点不关心 {@code react.cache}，而它的缺省值恰好就是
     * 「沿用旧行为」，因此让它们多写一个 {@code null} 只是噪音。
     *
     * @param maxRounds                 最大循环轮数
     * @param contextReserveTokens      上下文预留 token 数
     * @param maxToolOutputChars        单个工具输出最大字符数
     * @param compactKeepRecentMessages {@code /compact} 保留的最近消息条数
     * @param compactMaxSummaryChars    摘要长度上限
     * @param autoCompactPercent        自动压缩的触发百分比
     * @param toolOutput                工具结果落盘与上下文治理段
     */
    public ReactSettings(Integer maxRounds, Integer contextReserveTokens, Integer maxToolOutputChars,
                         Integer compactKeepRecentMessages, Integer compactMaxSummaryChars,
                         Integer autoCompactPercent, ToolOutputSettings toolOutput) {
        this(maxRounds, contextReserveTokens, maxToolOutputChars, compactKeepRecentMessages,
                compactMaxSummaryChars, autoCompactPercent, toolOutput, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param maxRounds                 最大循环轮数，非正数或缺省按缺省值处理
     * @param contextReserveTokens      上下文预留 token 数，负数或缺省按缺省值处理，{@code 0} 合法
     * @param maxToolOutputChars        单个工具输出最大字符数，非正数或缺省按缺省值处理
     * @param compactKeepRecentMessages {@code /compact} 保留的最近消息条数，负数或缺省按缺省值处理；
     *                                  {@code 0} 合法（表示全压）
     * @param compactMaxSummaryChars    摘要长度上限，非正数或缺省按缺省值处理
     * @param autoCompactPercent        自动压缩的触发百分比，负数或缺省按缺省值处理；
     *                                  {@code 0} 合法（关闭自动压缩），超过 100 按 100 处理
     * @param toolOutput                工具结果落盘与上下文治理段，{@code null} 按缺省值处理
     * @param cache                     提示词缓存治理段，{@code null} 按缺省值处理
     */
    @JsonCreator
    public ReactSettings(@JsonProperty("maxRounds") Integer maxRounds,
                         @JsonProperty("contextReserveTokens") Integer contextReserveTokens,
                         @JsonProperty("maxToolOutputChars") Integer maxToolOutputChars,
                         @JsonProperty("compactKeepRecentMessages") Integer compactKeepRecentMessages,
                         @JsonProperty("compactMaxSummaryChars") Integer compactMaxSummaryChars,
                         @JsonProperty("autoCompactPercent") Integer autoCompactPercent,
                         @JsonProperty("toolOutput") ToolOutputSettings toolOutput,
                         @JsonProperty("cache") ReactCacheSettings cache) {
        this.maxRounds = maxRounds != null && maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        this.contextReserveTokens = contextReserveTokens != null && contextReserveTokens >= 0
                ? contextReserveTokens : DEFAULT_CONTEXT_RESERVE_TOKENS;
        this.maxToolOutputChars = maxToolOutputChars != null && maxToolOutputChars > 0
                ? maxToolOutputChars : DEFAULT_MAX_TOOL_OUTPUT_CHARS;
        this.compactKeepRecentMessages = compactKeepRecentMessages != null && compactKeepRecentMessages >= 0
                ? compactKeepRecentMessages : DEFAULT_COMPACT_KEEP_RECENT_MESSAGES;
        this.compactMaxSummaryChars = compactMaxSummaryChars != null && compactMaxSummaryChars > 0
                ? compactMaxSummaryChars : DEFAULT_COMPACT_MAX_SUMMARY_CHARS;
        this.autoCompactPercent = autoCompactPercent != null && autoCompactPercent >= 0
                ? Math.min(autoCompactPercent, 100) : DEFAULT_AUTO_COMPACT_PERCENT;
        this.toolOutput = toolOutput == null ? new ToolOutputSettings() : toolOutput;
        this.cache = cache == null ? new ReactCacheSettings() : cache;
    }

    /**
     * 获取最大循环轮数。
     *
     * @return 最大循环轮数，保证为正
     */
    public int getMaxRounds() {
        return maxRounds;
    }

    /**
     * 获取上下文预留 token 数。
     *
     * @return 预留 token 数，保证非负
     */
    public int getContextReserveTokens() {
        return contextReserveTokens;
    }

    /**
     * 获取单个工具输出最大字符数。
     *
     * @return 最大字符数，保证为正
     */
    public int getMaxToolOutputChars() {
        return maxToolOutputChars;
    }

    /**
     * 获取 {@code /compact} 默认保留的最近消息条数。
     *
     * @return 保留条数，保证非负；{@code 0} 表示默认不保留原文
     */
    public int getCompactKeepRecentMessages() {
        return compactKeepRecentMessages;
    }

    /**
     * 获取压缩摘要长度上限。
     *
     * @return 字符数上限，保证为正
     */
    public int getCompactMaxSummaryChars() {
        return compactMaxSummaryChars;
    }

    /**
     * 获取自动压缩的触发百分比。
     *
     * @return 百分比，{@code 0} 表示关闭自动压缩，否则落在 {@code (0, 100]}
     */
    public int getAutoCompactPercent() {
        return autoCompactPercent;
    }

    /**
     * 获取工具结果落盘与上下文治理段。
     *
     * @return 工具结果设置，保证非 {@code null}
     */
    public ToolOutputSettings getToolOutput() {
        return toolOutput;
    }

    /**
     * 获取提示词缓存的治理段。
     *
     * @return 缓存设置，保证非 {@code null}
     */
    public ReactCacheSettings getCache() {
        return cache;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link JellyfishSettings#isEmpty()} 判断「整份运行期设置是否什么都没配」，
     * 因此只比较是否等于缺省，不比较字段来源。
     *
     * @return 全部字段（含工具结果段与缓存段）都等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return maxRounds == DEFAULT_MAX_ROUNDS
                && contextReserveTokens == DEFAULT_CONTEXT_RESERVE_TOKENS
                && maxToolOutputChars == DEFAULT_MAX_TOOL_OUTPUT_CHARS
                && compactKeepRecentMessages == DEFAULT_COMPACT_KEEP_RECENT_MESSAGES
                && compactMaxSummaryChars == DEFAULT_COMPACT_MAX_SUMMARY_CHARS
                && autoCompactPercent == DEFAULT_AUTO_COMPACT_PERCENT
                && toolOutput.isDefault()
                && cache.isDefault();
    }
}
