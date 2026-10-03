package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code subAgent} 段：子代理委派的运行期参数与预算（governor）。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。七个字段分三类：
 * <ul>
 *     <li><b>能不能委派</b>：{@code enabled} 是全局开关。关掉即不注册 {@code task} 工具——它服务于
 *     {@code -server} 这类多租户场景，那里不能让任意一个客户端把整机拖进一串模型调用；</li>
 *     <li><b>能走多远</b>：{@code maxDepth} 挡「一条链多深」，{@code maxSpawnsPerTurn} 挡
 *     「一层扇出多少」，{@code maxConcurrentRuns} 挡「全局同时在跑多少」——三者正交，
 *     缺任何一个都能被另一种方式绕过；</li>
 *     <li><b>跑多久、烧多少</b>：{@code runTimeoutMillis}（单 run 墙钟）、{@code runTokenBudget}
 *     （单 run 累计 token）、{@code treeTokenBudget}（一棵 run 树累计 token）、{@code maxRounds}
 *     （单 run 轮数）。它们<b>不</b>跟随 {@code react.maxRounds}：子代理被设计来干一件窄活，
 *     用主会话的额度会让跑偏的子代理消耗与主对话同等的成本。</li>
 * </ul>
 * <b>非法值一律回退缺省值而不是报错</b>，与 {@link ReactSettings} 同口径：配置问题不阻断启动。
 * {@code maxDepth} 允许显式配 {@code 0}（禁止委派），因此缺省判定以 {@code null} 区分
 * 「未配置」与「配了 0」；两个 token 预算允许显式配 {@code 0} 表示「不限制」。
 * <p>
 * <b>为什么 token 预算用绝对值而不是「模型上下文的比例」</b>：run 的预算是<b>累计消耗</b>
 * （它自然超过单次请求的上下文大小），与上下文窗口不是同一个量；用比例表示会给出一个随模型漂移、
 * 且与「跑了多少轮」无关的数。绝对值在所有模型上含义一致。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class SubAgentSettings {

    /** 是否启用子代理委派。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 允许的最大委派层数。缺省 1 表示只允许一层子代理（保守，深度可调）。 */
    public static final int DEFAULT_MAX_DEPTH = 1;

    /** 单个顶层回合内允许派生的子代理总数。 */
    public static final int DEFAULT_MAX_SPAWNS_PER_TURN = 3;

    /** 子代理单个回合的最大循环轮数。 */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    /** 全局同时运行的子代理 run 数上限。 */
    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 3;

    /** 单个 run 的墙钟上限（毫秒）。 */
    public static final long DEFAULT_RUN_TIMEOUT_MILLIS = 300_000L;

    /** 单个 run 的累计 token 上限。 */
    public static final long DEFAULT_RUN_TOKEN_BUDGET = 500_000L;

    /** 一棵 run 树的累计 token 上限。 */
    public static final long DEFAULT_TREE_TOKEN_BUDGET = 1_500_000L;

    /** 是否启用子代理委派。 */
    private final boolean enabled;

    /** 允许的最大委派层数。 */
    private final int maxDepth;

    /** 单个顶层回合内允许派生的子代理总数。 */
    private final int maxSpawnsPerTurn;

    /** 子代理单个回合的最大循环轮数。 */
    private final int maxRounds;

    /** 全局同时运行的子代理 run 数上限。 */
    private final int maxConcurrentRuns;

    /** 单个 run 的墙钟上限（毫秒）。 */
    private final long runTimeoutMillis;

    /** 单个 run 的累计 token 上限；{@code 0} 表示不限制。 */
    private final long runTokenBudget;

    /** 一棵 run 树的累计 token 上限；{@code 0} 表示不限制。 */
    private final long treeTokenBudget;

    /**
     * 构造缺省子代理设置。
     */
    public SubAgentSettings() {
        this(null, null, null, null, null, null, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param enabled           是否启用，{@code null} 按 {@link #DEFAULT_ENABLED} 处理
     * @param maxDepth          最大委派层数，{@code null} 或负数按缺省值处理，{@code 0} 合法（禁止委派）
     * @param maxSpawnsPerTurn  单回合子代理总数上限，非正数或 {@code null} 按缺省值处理
     * @param maxRounds         子代理回合最大轮数，非正数或 {@code null} 按缺省值处理
     * @param maxConcurrentRuns 全局同时运行的子代理 run 数上限，非正数或 {@code null} 按缺省值处理
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），非正数或 {@code null} 按缺省值处理
     * @param runTokenBudget    单 run 累计 token 上限，{@code null} 按缺省值处理，{@code 0} 表示不限制
     * @param treeTokenBudget   一棵 run 树累计 token 上限，{@code null} 按缺省值处理，{@code 0} 表示不限制
     */
    @JsonCreator
    public SubAgentSettings(@JsonProperty("enabled") Boolean enabled,
                            @JsonProperty("maxDepth") Integer maxDepth,
                            @JsonProperty("maxSpawnsPerTurn") Integer maxSpawnsPerTurn,
                            @JsonProperty("maxRounds") Integer maxRounds,
                            @JsonProperty("maxConcurrentRuns") Integer maxConcurrentRuns,
                            @JsonProperty("runTimeoutMillis") Long runTimeoutMillis,
                            @JsonProperty("runTokenBudget") Long runTokenBudget,
                            @JsonProperty("treeTokenBudget") Long treeTokenBudget) {
        this.enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        this.maxDepth = maxDepth != null && maxDepth >= 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        this.maxSpawnsPerTurn = maxSpawnsPerTurn != null && maxSpawnsPerTurn > 0
                ? maxSpawnsPerTurn : DEFAULT_MAX_SPAWNS_PER_TURN;
        this.maxRounds = maxRounds != null && maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        this.maxConcurrentRuns = maxConcurrentRuns != null && maxConcurrentRuns > 0
                ? maxConcurrentRuns : DEFAULT_MAX_CONCURRENT_RUNS;
        this.runTimeoutMillis = runTimeoutMillis != null && runTimeoutMillis > 0L
                ? runTimeoutMillis : DEFAULT_RUN_TIMEOUT_MILLIS;
        this.runTokenBudget = runTokenBudget == null ? DEFAULT_RUN_TOKEN_BUDGET
                : Math.max(0L, runTokenBudget);
        this.treeTokenBudget = treeTokenBudget == null ? DEFAULT_TREE_TOKEN_BUDGET
                : Math.max(0L, treeTokenBudget);
    }

    /**
     * 判断是否启用了子代理委派。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取允许的最大委派层数。
     *
     * @return 最大委派层数，保证非负；{@code 0} 表示禁止委派
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * 获取单个顶层回合内允许派生的子代理总数。
     *
     * @return 子代理总数上限，保证为正
     */
    public int getMaxSpawnsPerTurn() {
        return maxSpawnsPerTurn;
    }

    /**
     * 获取子代理单个回合的最大循环轮数。
     *
     * @return 最大轮数，保证为正
     */
    public int getMaxRounds() {
        return maxRounds;
    }

    /**
     * 获取全局同时运行的子代理 run 数上限。
     *
     * @return 并发上限，保证为正
     */
    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    /**
     * 获取单个 run 的墙钟上限。
     *
     * @return 墙钟上限（毫秒），保证为正
     */
    public long getRunTimeoutMillis() {
        return runTimeoutMillis;
    }

    /**
     * 获取单个 run 的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    public long getRunTokenBudget() {
        return runTokenBudget;
    }

    /**
     * 获取一棵 run 树的累计 token 上限。
     *
     * @return token 上限；{@code 0} 表示不限制
     */
    public long getTreeTokenBudget() {
        return treeTokenBudget;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link JellyfishSettings#isEmpty()} 判断「整份运行期设置是否什么都没配」，
     * 因此只比较是否等于缺省，不比较字段来源。
     *
     * @return 全部字段都等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return enabled == DEFAULT_ENABLED
                && maxDepth == DEFAULT_MAX_DEPTH
                && maxSpawnsPerTurn == DEFAULT_MAX_SPAWNS_PER_TURN
                && maxRounds == DEFAULT_MAX_ROUNDS
                && maxConcurrentRuns == DEFAULT_MAX_CONCURRENT_RUNS
                && runTimeoutMillis == DEFAULT_RUN_TIMEOUT_MILLIS
                && runTokenBudget == DEFAULT_RUN_TOKEN_BUDGET
                && treeTokenBudget == DEFAULT_TREE_TOKEN_BUDGET;
    }

    @Override
    public String toString() {
        return "SubAgentSettings{enabled=" + enabled + ", maxDepth=" + maxDepth
                + ", maxSpawnsPerTurn=" + maxSpawnsPerTurn + ", maxRounds=" + maxRounds
                + ", maxConcurrentRuns=" + maxConcurrentRuns
                + ", runTimeoutMillis=" + runTimeoutMillis
                + ", runTokenBudget=" + runTokenBudget
                + ", treeTokenBudget=" + treeTokenBudget + '}';
    }
}
