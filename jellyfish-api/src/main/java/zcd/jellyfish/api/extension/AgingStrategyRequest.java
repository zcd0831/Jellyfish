package zcd.jellyfish.api.extension;

/**
 * 老化策略请求：内核在组装一次请求、准备处理较早的工具结果之前构造，询问插件「这一次该怎么老化」。
 * <p>
 * <b>与压缩策略请求的分工</b>：本扩展点决定的是<b>常驻行为</b>——每轮组装请求都会问一次，
 * 因为「要不要开始老化、老化到哪」随上下文用量变化；而 {@link CompactionStrategyRequest} 只在真要
 * 压一次时才问。两者都只能改措辞与参数，都不掌握「删哪条消息」。
 * <p>
 * <b>拿不到消息正文</b>：本请求只带计数、预算与缺省参数，插件因此无法按内容挑挑拣拣。这是刻意的——
 * 老化算法是前缀不变量的守卫，而前缀不变量是全局性质（规则见 {@code docs/constraints.md} 的「上下文老化」）。
 * 插件仍能对 stub 文案说话：那是它自己工具输出的领域知识，见 {@link AgingStrategy}。
 * <p>
 * <b>实现约定</b>：本请求由内核在<b>调用点线程</b>同步派发，且<b>每一轮</b>都会派发，因此处理器必须
 * <b>只读且快</b>——不要做 I/O、不要阻塞、不要发布事件。任何抛出异常的处理器都只被记一条 WARN 并跳过，
 * 其余处理器照常参与。
 * <p>
 * <b>多个插件共存时的合并规则</b>：逐字段取「{@code order} 最小且该字段非 {@code null}」的那一个，
 * 与 {@link CompactionStrategyRequest} 一致。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AgingStrategyRequest extends ExtensionRequest<AgingStrategy> {

    /** 本次请求里的消息条数。 */
    private final int messageCount;

    /** 老化<b>之前</b>的上下文用量（token），衡量「若原样发出去会占多少」。 */
    private final int usedTokens;

    /** 上下文预算（token）；{@code <= 0} 表示模型没配窗口、无从判断。 */
    private final int budgetTokens;

    /** 当前压缩边界下标；{@code -1} 表示从未压过。 */
    private final int compressionBoundary;

    /** 配置里的缺省保留条数（插件要覆盖的就是它）。 */
    private final int defaultKeepRecentMessages;

    /** 配置里的缺省老化水位百分比（插件要覆盖的就是它）。 */
    private final int defaultAgingPercent;

    /**
     * 构造老化策略请求。
     *
     * @param sessionId                 会话标识，可为 {@code null}
     * @param messageCount              消息条数
     * @param usedTokens                老化前的上下文用量
     * @param budgetTokens              上下文预算
     * @param compressionBoundary       当前压缩边界下标
     * @param defaultKeepRecentMessages 配置里的缺省保留条数
     * @param defaultAgingPercent       配置里的缺省老化水位百分比
     */
    public AgingStrategyRequest(String sessionId, int messageCount, int usedTokens, int budgetTokens,
                                int compressionBoundary, int defaultKeepRecentMessages,
                                int defaultAgingPercent) {
        super(AgingStrategy.class, sessionId);
        this.messageCount = messageCount;
        this.usedTokens = usedTokens;
        this.budgetTokens = budgetTokens;
        this.compressionBoundary = compressionBoundary;
        this.defaultKeepRecentMessages = defaultKeepRecentMessages;
        this.defaultAgingPercent = defaultAgingPercent;
    }

    @Override
    public String getRouteKey() {
        // 类型级扩展点：同一会话允许多个插件各给一部分策略，由内核按 order 合并
        return null;
    }

    /**
     * 获取消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取老化前的上下文用量。
     * <p>
     * <b>为什么给的是「老化之前」的用量</b>：老化腾出空间后用量自然会降下来，若拿降下来之后的数字做
     * 判断，就会形成「腾出空间 → 用量降 → 下轮不老化 → 用量涨」的来回跳。内核对水位口径也是这么做的。
     *
     * @return 用量（token）
     */
    public int getUsedTokens() {
        return usedTokens;
    }

    /**
     * 获取上下文预算。
     *
     * @return 预算（token）；{@code <= 0} 表示模型没配窗口
     */
    public int getBudgetTokens() {
        return budgetTokens;
    }

    /**
     * 获取当前压缩边界下标。
     *
     * @return 边界下标；{@code -1} 表示从未压过
     */
    public int getCompressionBoundary() {
        return compressionBoundary;
    }

    /**
     * 获取配置里的缺省保留条数。
     *
     * @return 条数，非负
     */
    public int getDefaultKeepRecentMessages() {
        return defaultKeepRecentMessages;
    }

    /**
     * 获取配置里的缺省老化水位百分比。
     *
     * @return 百分比
     */
    public int getDefaultAgingPercent() {
        return defaultAgingPercent;
    }
}
