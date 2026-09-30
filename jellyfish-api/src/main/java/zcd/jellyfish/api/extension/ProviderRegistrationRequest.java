package zcd.jellyfish.api.extension;

/**
 * 厂商注册请求：内核按 {@code models.json} 里出现的 provider 类型，问「这个类型有人实现吗」。
 * <p>
 * <b>为什么需要这个扩展点</b>：内核此前只认 {@code type} 指向内置客户端的那几种厂商，
 * 于是本地 llama.cpp、企业自建网关、私有协议、自定义鉴权全都无路可走。本请求是插件接管这一层的入口。
 * <p>
 * <b>路由键是 provider 类型</b>（{@code models.json} 里的 {@code type}，已去空白并小写）：
 * 一种类型只能有一个实现，多个实现是<b>真歧义</b>而不是「按 order 取第一个」——同一份配置到底发给谁，
 * 没有人能回答。因此用 {@code handle} 而不是 {@code contribute}，重复注册按
 * {@link zcd.jellyfish.api.event.RegisterOptions} 的覆盖声明处理。
 * <p>
 * <b>内核自带的类型插件注册不进来</b>：{@code openai} / {@code claude} 这类类型已经在
 * {@code LlmClientFactory} 里，内核<b>不会</b>再问注册表，重复注册也会被拒。这不只是「先来后到」的
 * 优先级问题——传输请求里带的是<b>已解析好的 apiKey</b>，一个能顶替 {@code openai} 的插件
 * 就等于把所有用户的密钥转发到自己的服务器上。这条边界没有配置开关。
 * <p>
 * <b>0 个处理器时</b>：内核报「未知 provider 类型」，并给出可执行的下一步（装一个提供该类型的插件，
 * 或把它改成配置里已支持的类型），而不是原来那句只说「找不到客户端」的报错。
 * <p>
 * <b>失败语义</b>：处理器抛错时按「该类型不存在」处理（记 WARN 后回落到未知类型那条报错路径），
 * 不中断启动、不影响其它 provider。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ProviderRegistrationRequest extends ExtensionRequest<ProviderContribution> {

    /** provider 类型，已去空白并小写。 */
    private final String providerType;

    /**
     * 构造请求。
     *
     * @param providerType provider 类型，不可为空白
     */
    public ProviderRegistrationRequest(String providerType) {
        super(ProviderContribution.class, null);
        this.providerType = providerType;
    }

    @Override
    public String getRouteKey() {
        return providerType;
    }

    /**
     * 获取 provider 类型。
     *
     * @return provider 类型
     */
    public String getProviderType() {
        return providerType;
    }
}
