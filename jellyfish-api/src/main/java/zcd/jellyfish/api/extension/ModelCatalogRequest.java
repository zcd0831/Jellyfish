package zcd.jellyfish.api.extension;

/**
 * 模型目录请求：内核问「这个 provider 现在有哪些模型」。
 * <p>
 * <b>为什么需要动态发现</b>：本地推理服务的模型随磁盘上的文件变，企业网关的模型随后端部署变，
 * 而 {@code models.json} 是手写的。用户在配置里声明一个 provider、指向插件提供的类型，
 * 那条 provider 的 {@code models} 却只能靠手抄——发现能力把这一半补上。
 * <p>
 * <b>路由键是 provider 名</b>（{@code models.json} 里的 key），不是 provider 类型：模型属于
 * <b>provider 实例</b>，而一个传输实现可以服务同一类型下的多个 provider（两个不同网关的地址、
 * 两个不同账号）。按类型问会让插件分不清是在问哪一个。
 * <p>
 * <b>只有插件接管的类型会被问到</b>：内核自带的类型有固定的已知模型来源，问它们只是白跑一趟。
 * <p>
 * <b>不发凭据给这个请求</b>：插件要向自家端点发起查询，用它在
 * {@code jellyfish.json} 里的配置段（{@code PluginContext.configuration()}）——那是内核完成双源合并与
 * 环境变量插值之后给它的最终值，插件不需要也不允许自己读配置文件。{@code models.json} 里的 apiKey
 * 是给<b>调用</b>用的（见 {@code LlmTransportRequest}），不是给目录查询用的。
 * <p>
 * <b>什么时候会被问</b>：进程启动时插件全部就绪之后一次，以及每次 {@code /reload} 重启插件之后一次。
 * <b>发现结果不落盘</b>：{@code models.json} 始终是模型的唯一持久事实，发现只是让它临时更完整一点。
 * <p>
 * <b>0 个处理器时</b>：回落成配置里 {@code models} 写的那份，行为与没有这个扩展点时逐字段一致。
 * <p>
 * <b>失败语义</b>：处理器抛错时只记一条 WARN 并保留配置里的模型——目录发现失败不该让一个本来可用的
 * provider 变得不可用。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ModelCatalogRequest extends ExtensionRequest<ModelCatalogResult> {

    /** provider 名，即路由键。 */
    private final String providerName;

    /** provider 类型。 */
    private final String providerType;

    /**
     * 构造请求。
     *
     * @param providerName provider 名，不可为空白
     * @param providerType provider 类型，可为 {@code null}
     */
    public ModelCatalogRequest(String providerName, String providerType) {
        super(ModelCatalogResult.class, null);
        this.providerName = providerName;
        this.providerType = providerType;
    }

    @Override
    public String getRouteKey() {
        return providerName;
    }

    /**
     * 获取 provider 名。
     *
     * @return provider 名
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * 获取 provider 类型。
     *
     * @return provider 类型，可能为 {@code null}
     */
    public String getProviderType() {
        return providerType;
    }
}
