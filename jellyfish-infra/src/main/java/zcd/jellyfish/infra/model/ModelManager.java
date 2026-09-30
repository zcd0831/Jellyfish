package zcd.jellyfish.infra.model;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ModelCatalogRequest;
import zcd.jellyfish.api.extension.ModelCatalogResult;
import zcd.jellyfish.api.extension.ModelDescriptor;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ModelsLoadedEvent;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmClientFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型注册与路由门面：把「provider 名 + model 名」解析成 {@link ResolvedModel}，
 * 并按 provider 给出对应的 {@link LlmClient}。
 * <p>
 * <b>本类不持有任何全局「当前模型」状态</b>。当前用哪个 provider / model 属于会话运行态，
 * 由 Session 持有，同一进程内的不同会话可以各用各的模型、互不影响；这里只做
 * 「注册（按配置重建索引）→ 解析（名字 → Provider/Model）→ 路由（Provider → LlmClient）」。
 * <p>
 * 配置里的默认模型（{@code defaultProvider} / {@code defaultModel}）只作为<b>解析入口</b>存在，
 * 供会话初始化当前模型时取用，选择规则：
 * <ol>
 *     <li>默认 provider 与默认 model 都为空：取第一个有模型的 provider 及其第一个模型；</li>
 *     <li>只有默认 model：取第一个提供该 model 的 provider；</li>
 *     <li>只有默认 provider：取该 provider 的第一个模型；</li>
 *     <li>二者都有：精确匹配。</li>
 * </ol>
 * 注册 / 刷新阶段不校验配置是否可用（配置问题只发 {@code ConfigWarningEvent}，不打断启动）；
 * 解析不到时由 {@link #resolve(String, String)}、{@link #resolveDefault()} 抛
 * {@link JellyfishException}，即「真正用到时才报错」。
 *
 * @author zcd
 */
@Singleton
public class ModelManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ModelManager.class);

    /** 运行时配置门面，提供合并后的 provider 列表与默认 provider / model。 */
    private final RuntimeConfig runtimeConfig;

    /** provider / model 只读索引。 */
    private final ModelRegistry modelRegistry;

    /** LLM 客户端工厂，按 provider 缓存客户端。 */
    private final LlmClientFactory llmClientFactory;

    /** 通知发布入口，用于广播索引重建事件。 */
    private final EventPublisher events;

    /** 扩展注册表，用于向插件询问动态模型目录。 */
    private final ExtensionRegistry extensions;

    /**
     * 构造时建立索引。
     * <p>
     * 构造期只重建索引、不解析默认模型，也不广播 {@link ModelsLoadedEvent}：此刻
     * {@link RuntimeConfig} 尚未刷新（构造器不读配置），索引必为空，事件总线也可能还没启动——
     * 发出去只会是一条被缓冲重放的假事件。真正的装载发生在装配根调用 {@link #refresh(boolean)} 时。
     *
     * @param runtimeConfig    运行时配置门面
     * @param modelRegistry    provider / model 索引
     * @param llmClientFactory LLM 客户端工厂
     * @param events           通知发布入口
     * @param extensions       扩展注册表，用于查询插件接管的 provider 的动态目录
     */
    @Inject
    public ModelManager(RuntimeConfig runtimeConfig, ModelRegistry modelRegistry, LlmClientFactory llmClientFactory,
                        EventPublisher events, ExtensionRegistry extensions) {
        this.runtimeConfig = runtimeConfig;
        this.modelRegistry = modelRegistry;
        this.llmClientFactory = llmClientFactory;
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.extensions = extensions;
        rebuild(false);
    }

    /**
     * 刷新模型索引，并在重建后广播 {@link ModelsLoadedEvent}。
     * <p>
     * 事件表示「索引已重建」而不是「配置发生了变更」：本轮不做新旧快照 diff，因此首次装载与热更新
     * 发出的是同一种事件，且内容可以为空（“一个 provider 都没配”同样必须可见）。
     * 发布属 best-effort，失败不影响索引重建结果。
     *
     * @param reloadConfig 是否先重新读取配置文件（配置热更新时传 {@code true}）
     */
    public void refresh(boolean reloadConfig) {
        rebuild(reloadConfig);
        publishLoaded();
    }

    /**
     * 只重建索引、不广播事件。
     *
     * @param reloadConfig 是否先重新读取配置文件
     */
    private void rebuild(boolean reloadConfig) {
        if (reloadConfig) {
            runtimeConfig.refresh();
            // 配置可能变更 apiKey / baseUrl，旧客户端会以旧签名残留在缓存中且永不复用，这里显式清理
            llmClientFactory.clearCache();
        }
        modelRegistry.refresh(runtimeConfig.getProviders());
    }

    /**
     * 询问插件接管的 provider 现在有哪些模型，并按结果重建索引。
     * <p>
     * <b>为什么它是一个单独的方法，而不是塞进 {@link #refresh(boolean)} 里</b>：两个真实的顺序约束
     * 都把它指向「<b>插件就绪之后</b>」——启动时 {@code refresh} 跑在 {@code pluginManager.bootstrap()}
     * <b>之前</b>（那时一个插件都没起，问了也只会得到空目录），而 {@code /reload} 时
     * {@code refresh} 跑在 {@code pluginManager.reload()} <b>之前</b>（那时插件还是旧配置的实例，
     * 问回来的目录会陈旧到下次重载才修正）。因此它由装配根在两处分别调用，两处都在插件之后。
     * <p>
     * <b>只问插件接管的类型</b>：内核自带的类型有固定的模型来源，问它们只是白跑一趟。
     * <p>
     * <b>发现结果不落盘</b>：{@code models.json} 仍是模型的唯一持久事实；每次调用都从配置的 provider
     * 列表重新出发，因此不会把上一次发现到、这一次已经消失的模型留下。
     * <p>
     * <b>失败一定保留配置里的模型</b>：目录发现失败不该让一个本来可用的 provider 变得不可用。
     * 处理器抛错、返回空列表、返回的模型标识为空白，都按「没发现到」处理。
     */
    public void refreshCatalogs() {
        modelRegistry.refresh(withCatalogs(runtimeConfig.getProviders()));
        publishLoaded();
    }

    /**
     * 对每个由插件接管的 provider 询问一次目录，非空则整体替换其模型列表。
     *
     * @param providers 配置中的 provider 列表
     * @return 替换后的列表，与输入等长且顺序一致
     */
    private List<Provider> withCatalogs(List<Provider> providers) {
        if (providers == null || providers.isEmpty()) {
            return providers;
        }
        List<Provider> result = new ArrayList<Provider>(providers.size());
        for (Provider provider : providers) {
            result.add(provider == null ? null : withCatalog(provider));
        }
        return result;
    }

    /**
     * 询问单个 provider 的目录。
     *
     * @param provider provider
     * @return 发现到模型时返回副本，否则原样返回
     */
    private Provider withCatalog(Provider provider) {
        if (extensions == null || llmClientFactory.isBuiltinType(provider.getType())) {
            return provider;
        }
        ModelCatalogResult catalog = askCatalog(provider);
        if (catalog == null || !catalog.isPresent()) {
            return provider;
        }
        List<Model> models = new ArrayList<Model>();
        for (ModelDescriptor descriptor : catalog.getModels()) {
            if (descriptor == null || StringUtils.isBlank(descriptor.getId())) {
                continue;
            }
            models.add(new Model(descriptor.getId(), descriptor.getName(),
                    descriptor.getContextLength(), descriptor.getMaxOutputTokens()));
        }
        if (models.isEmpty()) {
            return provider;
        }
        LOG.info("插件模型目录已刷新: provider={} type={} models={}",
                provider.getName(), provider.getType(), models.size());
        return provider.withModels(models);
    }

    /**
     * 向注册表询问一个 provider 的目录。
     * <p>
     * 异常只记 WARN 并返回 {@code null}：目录发现是<b>锦上添花</b>，它失败的正确结果是
     * 「用回配置里写的模型」，而不是让启动或重载失败。
     *
     * @param provider provider
     * @return 目录结果，没人接管或处理失败时返回 {@code null}
     */
    private ModelCatalogResult askCatalog(Provider provider) {
        try {
            if (extensions.handlers(ModelCatalogRequest.class, provider.getName()).isEmpty()) {
                return null;
            }
            return extensions.invoke(extensions.handler(ModelCatalogRequest.class, provider.getName()),
                    new ModelCatalogRequest(provider.getName(), provider.getType()));
        } catch (RuntimeException e) {
            LOG.warn("插件模型目录查询失败: provider={} type={}",
                    provider.getName(), provider.getType(), e);
            return null;
        }
    }

    /**
     * 广播索引重建事件，发布失败只记日志。
     */
    private void publishLoaded() {
        try {
            events.publish(new ModelsLoadedEvent(runtimeConfig.getDefaultProvider(),
                    runtimeConfig.getDefaultModel(), providerNames()));
        } catch (RuntimeException e) {
            LOG.warn("模型装载事件发布失败", e);
        }
    }

    /**
     * 汇总当前索引到的 provider 名，供事件携带。
     *
     * @return provider 名集合，可能为空但不会为 {@code null}
     */
    private Set<String> providerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Provider provider : modelRegistry.getProviders()) {
            if (provider != null && StringUtils.isNotBlank(provider.getName())) {
                names.add(provider.getName());
            }
        }
        return names;
    }

    /**
     * 获取全部 provider。
     *
     * @return provider 列表，可能为空但不会为 {@code null}
     */
    public List<Provider> getProviders() {
        return modelRegistry.getProviders();
    }

    /**
     * 按名称查找 provider。
     *
     * @param providerName provider 名
     * @return 匹配的 provider，不存在时返回 {@code null}
     */
    public Provider findProvider(String providerName) {
        return modelRegistry.findProvider(providerName);
    }

    /**
     * 查找指定 provider 下的指定模型。
     *
     * @param providerName provider 名
     * @param modelName    model 名
     * @return 匹配的模型，不存在时返回 {@code null}
     */
    public Model findModel(String providerName, String modelName) {
        return modelRegistry.findModel(providerName, modelName);
    }

    /**
     * 精确解析指定 provider 下的指定模型。
     *
     * @param providerName provider 名
     * @param modelName    model 名
     * @return 解析结果
     * @throws JellyfishException provider / model 为空，或解析不到时抛出
     */
    public ResolvedModel resolve(String providerName, String modelName) {
        if (StringUtils.isAnyBlank(providerName, modelName)) {
            throw new JellyfishException("provider and model must not be blank");
        }
        Provider provider = modelRegistry.findProvider(providerName);
        Model model = modelRegistry.findModel(providerName, modelName);
        if (provider == null || model == null) {
            throw new JellyfishException("model not found: " + providerName + "/" + modelName);
        }
        return new ResolvedModel(provider, model);
    }

    /**
     * 按<b>模型引用</b>解析：{@code provider/model} 或裸 {@code model} 名。
     * <p>
     * 给「配置里写的是一个字符串」的调用点用：{@code /model} 命令的参数、agent 定义里的
     * {@code model} 字段都是这个形状。它只回答「这个名字解析成了哪个 provider 与 model」，
     * 不关心调用方接下来拿它干什么。
     * <p>
     * <b>裸 model 名取第一个提供它的 provider</b>：与 {@link #resolveDefault()} 只给 model 时的
     * 规则一致。两份规则合在一处，否则「/model gpt-4o」与「defaultModel: gpt-4o」会选到不同的 provider。
     * <p>
     * <b>斜杠必须在中间才算分隔符</b>：{@code foo/} 与 {@code /bar} 整串按裸 model 名处理。
     * 若把它们当成「provider 名为空」，用户会得到一条对他毫无指导价值的报错；
     * 而按模型名找不到时，报的是「没有任何 provider 提供该模型」，并把原文回给用户。
     *
     * @param reference 模型引用，不可为空白
     * @return 解析结果
     * @throws JellyfishException 引用为空白、或解析不到时抛出
     */
    public ResolvedModel resolveReference(String reference) {
        if (StringUtils.isBlank(reference)) {
            throw new JellyfishException("model reference must not be blank");
        }
        String trimmed = reference.trim();
        int slash = trimmed.indexOf('/');
        if (slash > 0 && slash < trimmed.length() - 1) {
            return resolve(trimmed.substring(0, slash), trimmed.substring(slash + 1));
        }
        return resolveByModelName(trimmed);
    }

    /**
     * 按配置的默认 provider / model 解析，供会话初始化「当前模型」时调用。
     *
     * @return 解析结果
     * @throws JellyfishException 没有任何可用 provider / model，或默认值引用不存在时抛出
     */
    public ResolvedModel resolveDefault() {
        String defaultProvider = runtimeConfig.getDefaultProvider();
        String defaultModel = runtimeConfig.getDefaultModel();

        if (StringUtils.isAllBlank(defaultProvider, defaultModel)) {
            Provider provider = requireFirstProviderWithModel();
            return new ResolvedModel(provider, requireFirstModel(provider.getName()));
        }
        if (StringUtils.isBlank(defaultProvider)) {
            return resolveByModelName(defaultModel);
        }
        if (StringUtils.isBlank(defaultModel)) {
            return new ResolvedModel(requireProvider(defaultProvider), requireFirstModel(defaultProvider));
        }
        return resolve(defaultProvider, defaultModel);
    }

    /**
     * 获取解析结果对应的 LLM 客户端。
     *
     * @param resolvedModel 已解析的 provider + model
     * @return LLM 客户端
     * @throws JellyfishException 解析结果为空或 provider 无法创建客户端时抛出
     */
    public LlmClient getClient(ResolvedModel resolvedModel) {
        if (resolvedModel == null) {
            throw new JellyfishException("resolved model must not be null");
        }
        return llmClientFactory.getClient(resolvedModel.getProvider());
    }

    /**
     * 解析指定模型并获取对应的 LLM 客户端。
     *
     * @param providerName provider 名
     * @param modelName    model 名
     * @return LLM 客户端
     * @throws JellyfishException 解析失败或 provider 无法创建客户端时抛出
     */
    public LlmClient getClient(String providerName, String modelName) {
        return getClient(resolve(providerName, modelName));
    }

    /**
     * 只给 model 名时，选取第一个提供该模型的 provider。
     *
     * @param modelName model 名
     * @return 解析结果
     * @throws JellyfishException 没有任何 provider 提供该模型时抛出
     */
    private ResolvedModel resolveByModelName(String modelName) {
        Map<String, Model> matches = modelRegistry.findProvidersByModelName(modelName);
        if (matches.isEmpty()) {
            throw new JellyfishException("no provider provides model: " + modelName);
        }
        Map.Entry<String, Model> first = matches.entrySet().iterator().next();
        return new ResolvedModel(requireProvider(first.getKey()), first.getValue());
    }

    /**
     * 获取第一个「配置了模型」的 provider。
     *
     * @return provider
     * @throws JellyfishException 没有任何 provider 配置模型时抛出
     */
    private Provider requireFirstProviderWithModel() {
        for (Provider provider : modelRegistry.getProviders()) {
            if (provider != null && provider.getModels() != null && !provider.getModels().isEmpty()) {
                return provider;
            }
        }
        throw new JellyfishException("no provider with model configured");
    }

    /**
     * 按名称获取 provider，不存在时报错。
     *
     * @param providerName provider 名
     * @return provider
     * @throws JellyfishException provider 不存在时抛出
     */
    private Provider requireProvider(String providerName) {
        Provider provider = modelRegistry.findProvider(providerName);
        if (provider == null) {
            throw new JellyfishException("provider not found: " + providerName);
        }
        return provider;
    }

    /**
     * 获取指定 provider 的第一个模型，不存在时报错。
     *
     * @param providerName provider 名
     * @return 第一个模型
     * @throws JellyfishException provider 不存在或没有模型时抛出
     */
    private Model requireFirstModel(String providerName) {
        Model model = modelRegistry.firstModel(providerName);
        if (model == null) {
            throw new JellyfishException("provider has no model: " + providerName);
        }
        return model;
    }
}
