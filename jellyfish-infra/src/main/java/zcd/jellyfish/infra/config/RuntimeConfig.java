package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * 运行时配置门面：统一负责所有配置文件的「全局级 + 项目级」双源读取与合并。
 * <p>
 * 合并规则（对每个配置段一致）：
 * <ul>
 *     <li>同名 provider 以项目级<b>整对象</b>替换全局级，避免同一 provider 的字段散落在两份文件中；</li>
 *     <li>默认 provider / model 以项目级非空值覆盖全局级，项目级未配置时回退全局级。</li>
 * </ul>
 * 配置内容在 {@link #refresh()} 时整体重建，并通过单个 {@code volatile} 字段一次性发布
 * {@link RuntimeSnapshot}，因此读取方看到的所有值必然来自同一份快照，不存在「新 provider + 旧默认值」的中间态。
 * <p>
 * 新增配置段时：声明字段 → 在 {@link #refresh()} 中用 {@link #load} 读取并合并 → 放入 {@link RuntimeSnapshot}，
 * 复用同一套双源机制。配置好坏的判定只发 {@link ConfigWarningEvent}、不抛错，启动期缺配置不会直接失败。
 * <p>
 * 依赖的是窄接口 {@link EventPublisher}，不感知具体事件总线实现。
 * <p>
 * <b>构造器不读取配置</b>：构造时只注入依赖，真正加载由装配根在 {@code AgentHarness.bootstrap()} 中、
 * 事件总线 {@code start()} 之后调用 {@link #refresh()} 触发。这样既去掉了「构造器里做 IO」的味道，
 * 也保证启动期告警一定发生在总线启动之后，不会因订阅者尚未注册而丢失。
 *
 * @author zcd
 */
@Singleton
public class RuntimeConfig {

    private final AppConfig appConfig;

    private final ConfigLoader configLoader;

    private final EventPublisher eventPublisher;

    /** 当前配置快照，整体替换保证读取一致性。 */
    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.empty();

    /**
     * 构造运行时配置门面。
     * <p>
     * 构造器只保存依赖，不读取任何配置文件；配置由装配根在事件总线启动后调用 {@link #refresh()} 加载。
     *
     * @param appConfig      应用级配置，提供各配置段的双源路径
     * @param configLoader   配置文件读取门面
     * @param eventPublisher 通知发布入口，用于广播配置告警
     */
    @Inject
    public RuntimeConfig(AppConfig appConfig, ConfigLoader configLoader, EventPublisher eventPublisher) {
        this.appConfig = appConfig;
        this.configLoader = configLoader;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 重新加载并合并全部配置段。
     * <p>
     * 首次加载由装配根在事件总线 {@code start()} 之后显式调用（见 {@code AgentHarness.bootstrap()}）；
     * 配置热更新时由上层再次调用。
     */
    public synchronized void refresh() {
        ModelSettings merged = load(appConfig.getModel(), ModelSettings.class, RuntimeConfig::mergeModelSettings);
        notifyIfInvalid(merged);
        this.snapshot = RuntimeSnapshot.of(merged);
    }

    /**
     * 通用双源读取：分别读取 global 与 project 文件，再交给 {@code merger} 合并。
     * <p>
     * 任一文件缺失或解析结果为空时以 {@code null} 传给 {@code merger}，由 {@code merger} 负责空值语义；
     * 两路径相同时只读取一次（此时「项目级覆盖」等价于单源）。
     *
     * @param paths  该配置段的双源路径，可为 {@code null}
     * @param type   配置文件的绑定类型，不可为 {@code null}
     * @param merger 合并函数，入参依次为 global、project（均可能为 {@code null}），必须返回非 {@code null}
     * @param <T>    配置类型
     * @return 合并后的配置，保证非 {@code null}
     * @throws JellyfishException merger 返回 {@code null} 时抛出
     */
    <T> T load(ConfigPaths paths, Class<T> type, BinaryOperator<T> merger) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(merger, "merger must not be null");
        String globalPath = paths == null ? null : paths.getGlobalPath();
        String projectPath = paths == null ? null : paths.getProjectPath();
        T global = read(globalPath, type);
        T project;
        if (isSamePath(globalPath, projectPath)) {
            // 同一份文件读一次即可，避免重复解析并产生两套等价对象
            project = null;
        } else {
            project = read(projectPath, type);
        }
        T merged = merger.apply(global, project);
        if (merged == null) {
            throw new JellyfishException("config merger returned null for type: " + type.getName());
        }
        return merged;
    }

    /**
     * 获取合并后的 provider 列表。
     *
     * @return provider 列表，可能为空但不会为 {@code null}
     */
    public List<Provider> getProviders() {
        return snapshot.getProviders();
    }

    /**
     * 获取合并后的默认 provider 名。
     *
     * @return 默认 provider 名，未配置时为 {@code null}
     */
    public String getDefaultProvider() {
        return snapshot.getModelSettings().getDefaultProvider();
    }

    /**
     * 获取合并后的默认 model 名。
     *
     * @return 默认 model 名，未配置时为 {@code null}
     */
    public String getDefaultModel() {
        return snapshot.getModelSettings().getDefaultModel();
    }

    /**
     * 获取合并后的模型配置快照。
     *
     * @return 模型配置，保证非 {@code null} 且只读
     */
    public ModelSettings getModelSettings() {
        return snapshot.getModelSettings();
    }

    /**
     * 读取单个配置段，并对「路径已配置但读不到内容」发出告警事件。
     *
     * @param path 完整文件路径，可为空
     * @param type 绑定类型
     * @param <T>  配置类型
     * @return 解析结果；路径为空或文件缺失时返回 {@code null}
     */
    private <T> T read(String path, Class<T> type) {
        T value = configLoader.read(path, type);
        if (value == null && StringUtils.isNotBlank(path)) {
            eventPublisher.publish(new ConfigWarningEvent(path, "配置段文件缺失或为空，将按未配置处理"));
        }
        return value;
    }

    /**
     * 判断 global 与 project 是否指向同一个已配置路径。
     *
     * @param globalPath  全局级路径，可为 {@code null}
     * @param projectPath 项目级路径，可为 {@code null}
     * @return 两者均为非空且字符串相等时返回 {@code true}
     */
    private static boolean isSamePath(String globalPath, String projectPath) {
        return StringUtils.isNotBlank(globalPath) && globalPath.equals(projectPath);
    }

    /**
     * 合并全局级与项目级模型配置，产出不可变结果。
     *
     * @param global  全局级配置，可为 {@code null}
     * @param project 项目级配置，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    private static ModelSettings mergeModelSettings(ModelSettings global, ModelSettings project) {
        Map<String, Provider> providers = new LinkedHashMap<>();
        putProviders(providers, global);
        // 同名 provider 整对象替换：项目级覆盖全局级，顺序保留全局级原有位置
        putProviders(providers, project);
        String defaultProvider = override(project, global, ModelSettings::getDefaultProvider);
        String defaultModel = override(project, global, ModelSettings::getDefaultModel);
        return new ModelSettings(defaultProvider, defaultModel, providers);
    }

    /**
     * 把一份配置中的 provider 以不可变副本写入目标映射，并用 map 的 key 回填 provider 名称，
     * 不修改原始 {@link Provider} 对象。
     *
     * @param target   目标映射
     * @param settings 待合并配置，可为 {@code null}
     */
    private static void putProviders(Map<String, Provider> target, ModelSettings settings) {
        if (settings == null) {
            return;
        }
        for (Map.Entry<String, Provider> entry : settings.getProviders().entrySet()) {
            Provider provider = entry.getValue();
            if (provider == null) {
                continue;
            }
            target.put(entry.getKey(), provider.withName(entry.getKey()));
        }
    }

    /**
     * 取项目级非空值，缺省时回退全局级。
     *
     * @param project  项目级配置，可为 {@code null}
     * @param global   全局级配置，可为 {@code null}
     * @param accessor 取值函数
     * @return 项目级非空值，否则全局级值
     */
    private static String override(ModelSettings project, ModelSettings global,
                                   Function<ModelSettings, String> accessor) {
        String projectValue = project == null ? null : accessor.apply(project);
        if (StringUtils.isNotBlank(projectValue)) {
            return projectValue;
        }
        return global == null ? null : accessor.apply(global);
    }

    /**
     * 对合并后的模型配置做一致性告警：默认值指向不存在的 provider / model 时只发出
     * {@link ConfigWarningEvent}，交由选择模型的 {@code ModelManager} 在真正用到时决定如何处理。
     *
     * @param merged 合并后的模型配置
     */
    private void notifyIfInvalid(ModelSettings merged) {
        Map<String, Provider> providers = merged.getProviders();
        if (providers.isEmpty()) {
            eventPublisher.publish(new ConfigWarningEvent("model", "模型配置未包含任何 provider"));
            return;
        }
        for (Provider provider : providers.values()) {
            if (StringUtils.isBlank(provider.getType())) {
                eventPublisher.publish(new ConfigWarningEvent(provider.getName(),
                        "provider 缺少 type，无法路由到具体客户端"));
            }
        }
        String defaultProvider = merged.getDefaultProvider();
        String defaultModel = merged.getDefaultModel();
        if (StringUtils.isNotBlank(defaultProvider) && !providers.containsKey(defaultProvider)) {
            eventPublisher.publish(new ConfigWarningEvent("model",
                    "默认 provider [" + defaultProvider + "] 不存在于 providers 中"));
            return;
        }
        if (StringUtils.isNotBlank(defaultModel) && !hasModel(providers, defaultProvider, defaultModel)) {
            eventPublisher.publish(new ConfigWarningEvent("model",
                    "默认 model [" + defaultModel + "] 未在任何 provider 中定义"));
        }
    }

    /**
     * 判断指定默认值下是否存在目标 model。
     *
     * @param providers       provider 映射
     * @param defaultProvider 默认 provider 名，可为空
     * @param modelName       目标 model 名
     * @return 存在返回 {@code true}
     */
    private static boolean hasModel(Map<String, Provider> providers, String defaultProvider, String modelName) {
        if (StringUtils.isNotBlank(defaultProvider)) {
            Provider provider = providers.get(defaultProvider);
            return provider != null && containsModel(provider, modelName);
        }
        for (Provider provider : providers.values()) {
            if (containsModel(provider, modelName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 provider 是否提供指定 model。
     *
     * @param provider  provider
     * @param modelName model 名
     * @return 提供返回 {@code true}
     */
    private static boolean containsModel(Provider provider, String modelName) {
        for (Model model : provider.getModels()) {
            if (model != null && modelName.equals(model.getName())) {
                return true;
            }
        }
        return false;
    }
}
