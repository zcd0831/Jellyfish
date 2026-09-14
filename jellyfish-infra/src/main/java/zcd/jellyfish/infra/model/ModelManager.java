package zcd.jellyfish.infra.model;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ModelsLoadedEvent;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmClientFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
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
     */
    @Inject
    public ModelManager(RuntimeConfig runtimeConfig, ModelRegistry modelRegistry, LlmClientFactory llmClientFactory,
                        EventPublisher events) {
        this.runtimeConfig = runtimeConfig;
        this.modelRegistry = modelRegistry;
        this.llmClientFactory = llmClientFactory;
        this.events = Objects.requireNonNull(events, "events must not be null");
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
