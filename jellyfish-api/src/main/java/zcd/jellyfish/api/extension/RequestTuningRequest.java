package zcd.jellyfish.api.extension;

/**
 * 请求调优请求：内核在组装完一次请求、即将发给厂商之前构造，询问插件「这一次的缓存参数要不要改」。
 * <p>
 * <b>为什么需要这个扩展点</b>：缓存相关的出站字段此前只能由 {@code models.json} 的 provider 配置定，
 * 插件完全够不着。于是两类只有插件才做得对的事做不到：
 * <ul>
 *     <li><b>厂商适配</b>——哪些字段目标端点认、哪些会被 400 拒绝，是<b>厂商与模型的知识</b>，
 *     而不是内核的知识。内核不认识某个新字段时不该被迫发版；</li>
 *     <li><b>按模型/按会话决定</b>——「这个模型前缀本来就每次在变，别打断点」这类判断需要看运行期状态，
 *     配置文件做不到。</li>
 * </ul>
 * <b>与 {@code models.json} 的 {@code vendorBody} 如何分工</b>：<b>静态的</b>厂商私有字段（用户已知端点认什么）
 * 走配置文件的透传段，不必为它装插件；本扩展点负责的是<b>要看运行期状态才决定得了</b>的那部分，
 * 以及下面三个缓存字段——它们不属于透传范围（{@code prompt_cache_key} 之类是内核保留键），
 * 只能由 provider 的 {@code cache} 段给基线、插件逐请求调整。
 * <p>
 * <b>能改的只有缓存参数</b>：返回的 {@link RequestTuning} 在<b>类型上</b>就没有
 * {@code systemPrompt} / {@code messages} / {@code tools} / {@code model} 这类字段，插件编译期即无法
 * 改写请求内容。理由见 {@link RequestTuning}：前缀不变量是全局性质，改内容的能力不能外放。
 * <p>
 * <b>什么时候会被问</b>：每次组装请求问一次。因此<b>一次用户回合可能问两次</b>——正式请求一次，
 * 压缩的 fork 或缓存保活请求再一次（它们各自是一次真实的调用，也各自需要正确的缓存参数）。
 * 处理器因此应当是<b>纯函数</b>：给定同样的请求字段返回同样的结果，不要把自己的状态推进寄望于
 * 「只会被问一次」。
 * <p>
 * <b>实现约定</b>：本请求由内核在<b>调用点线程</b>同步派发，因此处理器必须<b>只读且快</b>——不要做 I/O、
 * 不要阻塞、不要发布事件。任何抛出异常的处理器都只被记一条 WARN 并跳过，其余处理器照常参与。
 * <p>
 * <b>多个插件共存时的合并规则</b>：逐字段取「{@code order} 最小且该字段非 {@code null}」的那一个，
 * 与 {@link CompactionStrategyRequest} 一致——不是拼接，也不是取极值。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class RequestTuningRequest extends ExtensionRequest<RequestTuning> {

    /** provider 类型（{@code models.json} 里的 {@code type}），插件据此判断该厂商认哪些字段。 */
    private final String providerType;

    /** 模型标识。 */
    private final String modelId;

    /** 内核按缺省规则打算用的缓存路由键，插件可覆盖。 */
    private final String defaultCacheKey;

    /** 本次请求的消息条数（不含 system prompt）。 */
    private final int messageCount;

    /** 本次请求的工具条数。 */
    private final int toolCount;

    /**
     * 构造请求调优请求。
     *
     * @param sessionId       会话标识，可为 {@code null}
     * @param providerType    provider 类型，可为 {@code null}
     * @param modelId         模型标识，可为 {@code null}
     * @param defaultCacheKey 内核打算用的缓存路由键，可为 {@code null}
     * @param messageCount    消息条数
     * @param toolCount       工具条数
     */
    public RequestTuningRequest(String sessionId, String providerType, String modelId, String defaultCacheKey,
                                int messageCount, int toolCount) {
        super(RequestTuning.class, sessionId);
        this.providerType = providerType;
        this.modelId = modelId;
        this.defaultCacheKey = defaultCacheKey;
        this.messageCount = messageCount;
        this.toolCount = toolCount;
    }

    @Override
    public String getRouteKey() {
        // 类型级扩展点：同一会话允许多个插件各表一部分态度，由内核按 order 逐字段合并
        return null;
    }

    /**
     * 获取 provider 类型。
     *
     * @return provider 类型，可能为 {@code null}
     */
    public String getProviderType() {
        return providerType;
    }

    /**
     * 获取模型标识。
     *
     * @return 模型标识，可能为 {@code null}
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * 获取内核按缺省规则打算用的缓存路由键。
     * <p>
     * <b>为什么把它给插件看</b>：多数插件只是想「确认一下内核用的值合不合适」，而「不表态」
     * 正是「保持内核的值」。给出当前值，插件才不必自己重算一遍内核的规则。
     *
     * @return 缓存路由键，可能为 {@code null}（内核不打算下发）
     */
    public String getDefaultCacheKey() {
        return defaultCacheKey;
    }

    /**
     * 获取本次请求的消息条数。
     *
     * @return 消息条数
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * 获取本次请求的工具条数。
     *
     * @return 工具条数
     */
    public int getToolCount() {
        return toolCount;
    }
}
