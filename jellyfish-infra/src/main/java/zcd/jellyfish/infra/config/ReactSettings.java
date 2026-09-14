package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code react} 段：ReAct 循环的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾），只承载单份文件的内容。
 * 三项参数分别约束「一个回合最多几轮」「上下文预算给系统提示词留多少」「单个工具输出截多长」，
 * 都属于「用户可能想调、但内核必须有安全缺省」的量，因此缺省值写在类里而不是配置里。
 * <p>
 * 非法值（轮数与输出长度为非正数、预留为负数）一律回退到缺省值而不是报错：配置问题不阻断启动
 * 是本仓库的既有口径，真正的轮次约束与裁剪在运行期由 {@code ReActLooper} 与 {@code ContextWindow}
 * 兜底。预留 token 数允许显式配 {@code 0}（表示不留预留），因此缺省判定以 {@code null} 区分
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

    /** 最大循环轮数。 */
    private final int maxRounds;

    /** 上下文预算预留 token 数。 */
    private final int contextReserveTokens;

    /** 单个工具输出最大字符数。 */
    private final int maxToolOutputChars;

    /**
     * 构造缺省运行期参数。
     */
    public ReactSettings() {
        this(null, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param maxRounds            最大循环轮数，非正数或缺省按缺省值处理
     * @param contextReserveTokens 上下文预留 token 数，负数或缺省按缺省值处理，{@code 0} 合法
     * @param maxToolOutputChars   单个工具输出最大字符数，非正数或缺省按缺省值处理
     */
    @JsonCreator
    public ReactSettings(@JsonProperty("maxRounds") Integer maxRounds,
                         @JsonProperty("contextReserveTokens") Integer contextReserveTokens,
                         @JsonProperty("maxToolOutputChars") Integer maxToolOutputChars) {
        this.maxRounds = maxRounds != null && maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        this.contextReserveTokens = contextReserveTokens != null && contextReserveTokens >= 0
                ? contextReserveTokens : DEFAULT_CONTEXT_RESERVE_TOKENS;
        this.maxToolOutputChars = maxToolOutputChars != null && maxToolOutputChars > 0
                ? maxToolOutputChars : DEFAULT_MAX_TOOL_OUTPUT_CHARS;
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
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link JellyfishSettings#isEmpty()} 判断「整份运行期设置是否什么都没配」，
     * 因此只比较是否等于缺省，不比较字段来源。
     *
     * @return 三项都等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return maxRounds == DEFAULT_MAX_ROUNDS
                && contextReserveTokens == DEFAULT_CONTEXT_RESERVE_TOKENS
                && maxToolOutputChars == DEFAULT_MAX_TOOL_OUTPUT_CHARS;
    }
}
