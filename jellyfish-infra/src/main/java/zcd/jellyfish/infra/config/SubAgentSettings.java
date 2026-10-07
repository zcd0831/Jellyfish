package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 的 {@code subAgent} 段：子代理委派的运行期参数与预算（governor）。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾）。字段分三类：
 * <ul>
 *     <li><b>能不能委派</b>：{@code enabled} 是全局开关。关掉即不注册 {@code task} 工具——它服务于
 *     {@code -server} 这类多租户场景，那里不能让任意一个客户端把整机拖进一串模型调用；</li>
 *     <li><b>能走多远、挤不下怎么办</b>：{@code maxDepth} 挡「一条链多深」，{@code maxSpawnsPerTurn} 挡
 *     「一层扇出多少」，{@code maxConcurrentRuns} 挡「全局同时在跑多少」——三者正交，
 *     缺任何一个都能被另一种方式绕过；{@code maxQueuedRuns} 是并发满员后的等待区，让超出的 run
 *     <b>排队而不是当场失败</b>（代价见该字段的说明：排队期间不受墙钟约束）；</li>
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

    /**
     * 单个顶层回合内允许派生的子代理总数。
     * <p>
     * <b>它不是「一层扇出多少」，而是整个回合的累计额</b>：派生一个少一个，与并发上限
     * （{@link #DEFAULT_MAX_CONCURRENT_RUNS}，超出的 run 排队等待而非失败）是两回事。
     * <p>
     * <b>取 12 是为了让编排类用法开箱可用</b>：一次声明式编排常见形状是「调研 → 几路并行 → 复核 → 汇总」，
     * 步骤数以十几计；取 3 会让第 4 个派生就被拒，而失败长得像「某几个步骤坏了」。
     * 并发仍然由 {@code maxConcurrentRuns} 门控，因此调大这个数只放宽「一个回合能问多少个子代理」，
     * 不放宽「同时跑多少个」。
     */
    public static final int DEFAULT_MAX_SPAWNS_PER_TURN = 12;

    /** 子代理单个回合的最大循环轮数。 */
    public static final int DEFAULT_MAX_ROUNDS = 8;

    /** 全局同时运行的子代理 run 数上限。 */
    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 3;

    /**
     * 并发满员后允许积压的 run 数上限。
     * <p>
     * <b>它为什么存在</b>：并发是硬上限（它保护的是模型调用配额与机器负载），但「这一批派得比并发多」
     * 不该等于「多出来的当场失败」——一次扇出 8 个而并发是 3 就是常见情形，失败会让编排方被迫自己
     * 手工分批。有这一截等待区，超出的 run 排队等许可，扇出多少由 {@code maxSpawnsPerTurn} 与
     * {@code maxDepth} 负责约束。
     * <p>
     * <b>为什么必须有界且不允许 0 表示无界</b>：无界队列会把「编排跑飞」从「一批失败」放大成
     * 「内存被排队任务吃光」，而每个排队任务都捏着一份执行体与子会话句柄；有界队列满时仍然当场失败，
     * 保留这条兜底。
     * <p>
     * <b>排队代价</b>：排队中的 run <b>不计入墙钟</b>（{@code runTimeoutMillis} 从真正开始执行起算），
     * 因此「一直排不上」不会被任何超时兜住——靠的是能派多少由上面的三道上限约束，以及用户中止回合
     * 时的级联取消。
     */
    public static final int DEFAULT_MAX_QUEUED_RUNS = 64;

    /** 单个 run 的墙钟上限（毫秒）。 */
    public static final long DEFAULT_RUN_TIMEOUT_MILLIS = 300_000L;

    /**
     * 单个 run 的累计 token 上限。
     * <p>
     * <b>它通常比树预算先撞上</b>：预算是累计口径（每轮的输入与输出都算进去），因此「上下文里带着
     * 一份大材料、又跑很多轮」的子代理会先触到它。{@link #DEFAULT_TREE_TOKEN_BUDGET} 刻意是它的
     * {@link #DEFAULT_MAX_SPAWNS_PER_TURN} 倍，只为「所有子代理都跑满各自上限」封顶。
     */
    public static final long DEFAULT_RUN_TOKEN_BUDGET = 500_000L;

    /**
     * 一棵 run 树的累计 token 上限。
     * <p>
     * <b>它不是一道独立的限制，而是「所有子代理都跑满各自上限」的封顶</b>：缺省值刻意等于
     * {@link #DEFAULT_MAX_SPAWNS_PER_TURN} × {@link #DEFAULT_RUN_TOKEN_BUDGET}（12 × 50 万）。
     * 比这个乘积小时，树预算会先于单 run 预算生效——那时个别子代理明明没触到自己的上限却被截断，
     * 而表现是「某几个步骤失败了」，看不出真正的原因。
     * <p>
     * 可以用它主动收紧总花费（写小是有意义的），但**调大派生上限时要一起调大**，否则那道限制会
     * 悄悄回落到树上。写 {@code 0} 表示不限制（单 run 预算仍然各管各的）。
     */
    public static final long DEFAULT_TREE_TOKEN_BUDGET = 6_000_000L;

    /**
     * 归档目录下最多保留的 run 归档文件数。
     * <p>
     * <b>为什么与工具输出分开算</b>：归档是按 run（而不是按工具调用）产生的，一个 run 会含
     * 整份子会话 transcript，个体远比一份工具结果大；共用一个预算的话，一次长任务的归档就能把
     * 「可回查的工具结果」挤干净，而两者本该互不掏空对方的窗口。
     */
    public static final int DEFAULT_ARCHIVE_KEEP_FILES = 200;

    /**
     * 归档目录最多占用的字节数（100 MiB）。
     * <p>
     * 刻意比工具输出（50 MiB）宽：归档含完整 transcript，而它存在的意义就是「事后能回看」，
     * 窗口太短会让这个意义落空。它仍然是有上限的——归档是观测窗口，不是合规归档。
     */
    public static final long DEFAULT_ARCHIVE_MAX_BYTES = 100L * 1024L * 1024L;

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

    /** 并发满员后允许积压的 run 数上限。 */
    private final int maxQueuedRuns;

    /** 单个 run 的墙钟上限（毫秒）。 */
    private final long runTimeoutMillis;

    /** 单个 run 的累计 token 上限；{@code 0} 表示不限制。 */
    private final long runTokenBudget;

    /** 一棵 run 树的累计 token 上限；{@code 0} 表示不限制。 */
    private final long treeTokenBudget;

    /** 归档目录最多保留的文件数；{@code 0} 表示不清理。 */
    private final int archiveKeepFiles;

    /** 归档目录最多占用的字节数；{@code 0} 表示不清理。 */
    private final long archiveMaxBytes;

    /**
     * 构造缺省子代理设置。
     */
    public SubAgentSettings() {
        this(null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 构造子代理设置（归档配额取缺省）。
     * <p>
     * 保留这个重载是为了让「只关心并发 / 预算那几个旋钮」的调用点不必多写两个 {@code null}——
     * 反序列化走的是下面那个全参构造器。
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
    public SubAgentSettings(Boolean enabled, Integer maxDepth, Integer maxSpawnsPerTurn, Integer maxRounds,
                            Integer maxConcurrentRuns, Long runTimeoutMillis, Long runTokenBudget,
                            Long treeTokenBudget) {
        this(enabled, maxDepth, maxSpawnsPerTurn, maxRounds, maxConcurrentRuns, null, runTimeoutMillis,
                runTokenBudget, treeTokenBudget, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param enabled           是否启用，{@code null} 按 {@link #DEFAULT_ENABLED} 处理
     * @param maxDepth          最大委派层数，{@code null} 或负数按缺省值处理，{@code 0} 合法（禁止委派）
     * @param maxSpawnsPerTurn  单回合子代理总数上限，非正数或 {@code null} 按缺省值处理
     * @param maxRounds         子代理回合最大轮数，非正数或 {@code null} 按缺省值处理
     * @param maxConcurrentRuns 全局同时运行的子代理 run 数上限，非正数或 {@code null} 按缺省值处理
     * @param maxQueuedRuns     并发满员后允许积压的 run 数上限，非正数或 {@code null} 按缺省值处理
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），非正数或 {@code null} 按缺省值处理
     * @param runTokenBudget    单 run 累计 token 上限，{@code null} 按缺省值处理，{@code 0} 表示不限制
     * @param treeTokenBudget   一棵 run 树累计 token 上限，{@code null} 按缺省值处理，{@code 0} 表示不限制
     * @param archiveKeepFiles  归档目录最多保留的文件数，负数或 {@code null} 按缺省值处理；
     *                          {@code 0} 合法（表示不清理）
     * @param archiveMaxBytes   归档目录最多占用的字节数，负数或 {@code null} 按缺省值处理；
     *                          {@code 0} 合法（表示不清理）
     */
    @JsonCreator
    public SubAgentSettings(@JsonProperty("enabled") Boolean enabled,
                            @JsonProperty("maxDepth") Integer maxDepth,
                            @JsonProperty("maxSpawnsPerTurn") Integer maxSpawnsPerTurn,
                            @JsonProperty("maxRounds") Integer maxRounds,
                            @JsonProperty("maxConcurrentRuns") Integer maxConcurrentRuns,
                            @JsonProperty("maxQueuedRuns") Integer maxQueuedRuns,
                            @JsonProperty("runTimeoutMillis") Long runTimeoutMillis,
                            @JsonProperty("runTokenBudget") Long runTokenBudget,
                            @JsonProperty("treeTokenBudget") Long treeTokenBudget,
                            @JsonProperty("archiveKeepFiles") Integer archiveKeepFiles,
                            @JsonProperty("archiveMaxBytes") Long archiveMaxBytes) {
        this.enabled = enabled == null ? DEFAULT_ENABLED : enabled;
        this.maxDepth = maxDepth != null && maxDepth >= 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        this.maxSpawnsPerTurn = maxSpawnsPerTurn != null && maxSpawnsPerTurn > 0
                ? maxSpawnsPerTurn : DEFAULT_MAX_SPAWNS_PER_TURN;
        this.maxRounds = maxRounds != null && maxRounds > 0 ? maxRounds : DEFAULT_MAX_ROUNDS;
        this.maxConcurrentRuns = maxConcurrentRuns != null && maxConcurrentRuns > 0
                ? maxConcurrentRuns : DEFAULT_MAX_CONCURRENT_RUNS;
        this.maxQueuedRuns = maxQueuedRuns != null && maxQueuedRuns > 0
                ? maxQueuedRuns : DEFAULT_MAX_QUEUED_RUNS;
        this.runTimeoutMillis = runTimeoutMillis != null && runTimeoutMillis > 0L
                ? runTimeoutMillis : DEFAULT_RUN_TIMEOUT_MILLIS;
        this.runTokenBudget = runTokenBudget == null ? DEFAULT_RUN_TOKEN_BUDGET
                : Math.max(0L, runTokenBudget);
        this.treeTokenBudget = treeTokenBudget == null ? DEFAULT_TREE_TOKEN_BUDGET
                : Math.max(0L, treeTokenBudget);
        this.archiveKeepFiles = archiveKeepFiles != null && archiveKeepFiles >= 0
                ? archiveKeepFiles : DEFAULT_ARCHIVE_KEEP_FILES;
        this.archiveMaxBytes = archiveMaxBytes != null && archiveMaxBytes >= 0
                ? archiveMaxBytes : DEFAULT_ARCHIVE_MAX_BYTES;
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
     * 获取并发满员后允许积压的 run 数上限。
     * <p>
     * <b>它不参与并发控制</b>：能同时跑多少只由 {@link #getMaxConcurrentRuns()} 决定，本值只回答
     * 「挤不下时允许多少个在后面等」。因此调大它不会让更多 run 并行，只会把「当场失败」推迟到
     * 更晚的积压点。
     *
     * @return 排队上限，保证为正
     */
    public int getMaxQueuedRuns() {
        return maxQueuedRuns;
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
     * 获取归档目录最多保留的文件数。
     *
     * @return 文件数上限，保证非负；{@code 0} 表示不清理
     */
    public int getArchiveKeepFiles() {
        return archiveKeepFiles;
    }

    /**
     * 获取归档目录最多占用的字节数。
     *
     * @return 字节上限，保证非负；{@code 0} 表示不清理
     */
    public long getArchiveMaxBytes() {
        return archiveMaxBytes;
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
                && maxQueuedRuns == DEFAULT_MAX_QUEUED_RUNS
                && runTimeoutMillis == DEFAULT_RUN_TIMEOUT_MILLIS
                && runTokenBudget == DEFAULT_RUN_TOKEN_BUDGET
                && treeTokenBudget == DEFAULT_TREE_TOKEN_BUDGET
                && archiveKeepFiles == DEFAULT_ARCHIVE_KEEP_FILES
                && archiveMaxBytes == DEFAULT_ARCHIVE_MAX_BYTES;
    }

    @Override
    public String toString() {
        return "SubAgentSettings{enabled=" + enabled + ", maxDepth=" + maxDepth
                + ", maxSpawnsPerTurn=" + maxSpawnsPerTurn + ", maxRounds=" + maxRounds
                + ", maxConcurrentRuns=" + maxConcurrentRuns
                + ", maxQueuedRuns=" + maxQueuedRuns
                + ", runTimeoutMillis=" + runTimeoutMillis
                + ", runTokenBudget=" + runTokenBudget
                + ", treeTokenBudget=" + treeTokenBudget + '}';
    }
}
