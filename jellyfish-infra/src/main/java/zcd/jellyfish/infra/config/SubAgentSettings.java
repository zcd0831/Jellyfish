package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code subAgent} 段：子代理委派的运行期参数。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。四项各自约束「委派这件事能走多远」：
 * <ul>
 *     <li>{@code enabled}：全局开关。关掉即不注册 {@code task} 工具——它服务于
 *     {@code -server} 这类多租户场景，那里不能让任意一个客户端把整机拖进一串模型调用；</li>
 *     <li>{@code maxDepth}：允许的最大委派层数。{@code 2} 表示「主会话 → 子代理 → 孙代理」；
 *     {@code 0} 表示禁止任何委派；</li>
 *     <li>{@code maxSpawnsPerTurn}：单个顶层回合内累计可派生的子代理数。深度只挡「一条链走多深」，
 *     挡不住「每层扇出多少」——3 层 × 每层 8 个就是 24 次模型调用，因此两个上限都要有；</li>
 *     <li>{@code maxRounds}：子代理自己那个回合的最大循环轮数。它<b>不</b>跟随
 *     {@code react.maxRounds}：子代理被设计来干一件窄活，用主会话的轮数上限会让跑偏的子代理
 *     消耗与主对话同等的额度。</li>
 * </ul>
 * <b>非法值一律回退缺省值而不是报错</b>，与 {@link ReactSettings} 同口径：配置问题不阻断启动。
 * {@code maxDepth} 允许显式配 {@code 0}（禁止委派），因此缺省判定以 {@code null} 区分
 * 「未配置」与「配了 0」。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class SubAgentSettings {

    /** 是否启用子代理委派。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 允许的最大委派层数。 */
    public static final int DEFAULT_MAX_DEPTH = 2;

    /** 单个顶层回合内允许派生的子代理总数。 */
    public static final int DEFAULT_MAX_SPAWNS_PER_TURN = 32;

    /** 子代理单个回合的最大循环轮数。 */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    /** 是否启用子代理委派。 */
    private final boolean enabled;

    /** 允许的最大委派层数。 */
    private final int maxDepth;

    /** 单个顶层回合内允许派生的子代理总数。 */
    private final int maxSpawnsPerTurn;

    /** 子代理单个回合的最大循环轮数。 */
    private final int maxRounds;

    /**
     * 构造缺省子代理设置。
     */
    public SubAgentSettings() {
        this(null, null, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param enabled         是否启用，{@code null} 按 {@link #DEFAULT_ENABLED} 处理
     * @param maxDepth        最大委派层数，{@code null} 或负数按缺省值处理，{@code 0} 合法（禁止委派）
     * @param maxSpawnsPerTurn 单回合子代理总数上限，非正数或 {@code null} 按缺省值处理
     * @param maxRounds       子代理回合最大轮数，非正数或 {@code null} 按缺省值处理
     */
    @JsonCreator
    public SubAgentSettings(@JsonProperty("enabled") Boolean enabled,
                            @JsonProperty("maxDepth") Integer maxDepth,
                            @JsonProperty("maxSpawnsPerTurn") Integer maxSpawnsPerTurn,
                            @JsonProperty("maxRounds") Integer maxRounds) {
        this.enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        this.maxDepth = maxDepth != null && maxDepth >= 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        this.maxSpawnsPerTurn = maxSpawnsPerTurn != null && maxSpawnsPerTurn > 0
                ? maxSpawnsPerTurn : DEFAULT_MAX_SPAWNS_PER_TURN;
        this.maxRounds = maxRounds != null && maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
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
                && maxRounds == DEFAULT_MAX_ROUNDS;
    }

    @Override
    public String toString() {
        return "SubAgentSettings{enabled=" + enabled + ", maxDepth=" + maxDepth
                + ", maxSpawnsPerTurn=" + maxSpawnsPerTurn + ", maxRounds=" + maxRounds + '}';
    }
}
