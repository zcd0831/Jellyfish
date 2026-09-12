package zcd.jellyfish.infra.model;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * provider / model 的只读索引，供 {@link ModelManager} 做模型解析与路由。
 * <p>
 * 索引本身不持有任何「当前模型」语义：每次刷新整体重建，解析由调用方按名字发起。
 * 与旧实现的关键差异：索引以 provider 名为 key，而不是以 {@link Provider} 对象为 key，
 * 且每次刷新整体重建而非增量累加，避免配置热更新后残留旧对象。
 *
 * @author zcd
 */
@Singleton
public class ModelRegistry {

    /** provider 名 → provider 定义，顺序与配置一致。 */
    private volatile Map<String, Provider> providersByName = Collections.emptyMap();

    /** model 名 → (provider 名 → model)，用于「只给 model 名」时反查 provider。 */
    private volatile Map<String, Map<String, Model>> providersByModelName = Collections.emptyMap();

    /**
     * 无状态索引，构造器仅供 Dagger 注入。
     */
    @Inject
    public ModelRegistry() {
    }

    /**
     * 用最新配置整体重建索引。
     *
     * @param providers 合并后的 provider 列表，可为 {@code null}
     */
    public synchronized void refresh(List<Provider> providers) {
        Map<String, Provider> byName = new LinkedHashMap<>();
        Map<String, Map<String, Model>> byModelName = new LinkedHashMap<>();
        if (providers != null) {
            for (Provider provider : providers) {
                indexProvider(byName, byModelName, provider);
            }
        }
        this.providersByName = Collections.unmodifiableMap(byName);
        this.providersByModelName = Collections.unmodifiableMap(byModelName);
    }

    /**
     * 获取全部 provider。
     *
     * @return provider 列表，可能为空但不会为 {@code null}
     */
    public List<Provider> getProviders() {
        return new ArrayList<>(providersByName.values());
    }

    /**
     * 按名称查找 provider。
     *
     * @param providerName provider 名
     * @return 匹配的 provider，不存在时返回 {@code null}
     */
    public Provider findProvider(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return null;
        }
        return providersByName.get(providerName);
    }

    /**
     * 查找指定 provider 下的指定模型。
     *
     * @param providerName provider 名
     * @param modelName    model 名
     * @return 匹配的模型，不存在时返回 {@code null}
     */
    public Model findModel(String providerName, String modelName) {
        Provider provider = findProvider(providerName);
        if (provider == null || provider.getModels() == null) {
            return null;
        }
        for (Model model : provider.getModels()) {
            if (model != null && modelName != null && modelName.equals(model.getName())) {
                return model;
            }
        }
        return null;
    }

    /**
     * 获取指定 provider 的第一个模型。
     *
     * @param providerName provider 名
     * @return 第一个模型，provider 不存在或没有模型时返回 {@code null}
     */
    public Model firstModel(String providerName) {
        Provider provider = findProvider(providerName);
        if (provider == null || provider.getModels() == null || provider.getModels().isEmpty()) {
            return null;
        }
        return provider.getModels().get(0);
    }

    /**
     * 反查提供指定模型的 provider，按配置顺序返回，用于「只给了 model 名」的场景。
     *
     * @param modelName model 名
     * @return provider 名 → model 的映射，可能为空但不会为 {@code null}
     */
    public Map<String, Model> findProvidersByModelName(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return Collections.emptyMap();
        }
        Map<String, Model> result = providersByModelName.get(modelName);
        return result == null ? Collections.<String, Model>emptyMap() : result;
    }

    /**
     * 索引单个 provider 及其模型。
     *
     * @param byName       provider 名索引
     * @param byModelName  model 名反查索引
     * @param provider     待索引 provider，可为 {@code null}
     */
    private static void indexProvider(Map<String, Provider> byName,
                                      Map<String, Map<String, Model>> byModelName,
                                      Provider provider) {
        if (provider == null || StringUtils.isBlank(provider.getName())) {
            return;
        }
        byName.put(provider.getName(), provider);
        if (provider.getModels() == null) {
            return;
        }
        for (Model model : provider.getModels()) {
            if (model == null || StringUtils.isBlank(model.getName())) {
                continue;
            }
            Map<String, Model> providers = byModelName.get(model.getName());
            if (providers == null) {
                providers = new LinkedHashMap<>();
                byModelName.put(model.getName(), providers);
            }
            providers.put(provider.getName(), model);
        }
    }
}
