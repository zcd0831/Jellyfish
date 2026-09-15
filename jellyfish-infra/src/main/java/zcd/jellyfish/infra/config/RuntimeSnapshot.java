package zcd.jellyfish.infra.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link RuntimeConfig} 对外发布的不可变配置快照。
 * <p>
 * 字段与「配置文件 → 配置类」一一对应：{@link ModelSettings}（models.json）、
 * {@link AgentSettings}（agents.json）、{@link JellyfishSettings}（jellyfish.json），
 * 外加从模型段派生出的 provider 列表、从 {@code config.json} 读出的插件扫描根目录，
 * 以及不来自任何双源配置的 {@link AgentDefinition}（内置的 {@code default-agent.json}）。
 * 所有字段在构造时确定、无 setter，且集合已做不可变包装；
 * {@link RuntimeConfig} 用它做单点 {@code volatile} 发布，保证读取方要么看到完整的新快照、
 * 要么看到完整的旧快照。
 *
 * @author zcd
 */
public final class RuntimeSnapshot {

    /** 合并后的模型配置。 */
    private final ModelSettings modelSettings;

    /** 合并后的 agent 配置。 */
    private final AgentSettings agentSettings;

    /** 内置默认 agent，随构件发布，不参与双源合并。 */
    private final AgentDefinition systemAgent;

    /** 合并后的运行期设置（插件段）。 */
    private final JellyfishSettings jellyfishSettings;

    /** 合并后的 provider 列表，顺序与配置文件一致。 */
    private final List<Provider> providers;

    /** 插件扫描根目录，已展开 {@code ~} 且已丢弃空白条目。 */
    private final List<Path> pluginRoots;

    /**
     * 私有构造器，只允许通过工厂方法创建完整快照。
     *
     * @param modelSettings    模型配置
     * @param agentSettings    agent 配置
     * @param jellyfishSettings 运行期设置
     * @param providers        provider 列表（已不可变）
     * @param pluginRoots      插件扫描根目录（已不可变）
     * @param systemAgent      内置默认 agent，可为 {@code null}（未加载配置时）
     */
    private RuntimeSnapshot(ModelSettings modelSettings, AgentSettings agentSettings,
                            JellyfishSettings jellyfishSettings, List<Provider> providers,
                            List<Path> pluginRoots, AgentDefinition systemAgent) {
        this.modelSettings = modelSettings;
        this.agentSettings = agentSettings;
        this.jellyfishSettings = jellyfishSettings;
        this.providers = providers;
        this.pluginRoots = pluginRoots;
        this.systemAgent = systemAgent;
    }

    /**
     * 构造未加载任何配置的空快照。
     *
     * @return 空快照
     */
    public static RuntimeSnapshot empty() {
        return new RuntimeSnapshot(new ModelSettings(null, null, null), new AgentSettings(null),
                new JellyfishSettings(null, null), Collections.<Provider>emptyList(),
                Collections.<Path>emptyList(), null);
    }

    /**
     * 由合并后的各段配置构造快照，并从模型段派生出 provider 列表。
     *
     * @param modelSettings     合并后的模型配置，不可为 {@code null}
     * @param agentSettings     合并后的 agent 配置，不可为 {@code null}
     * @param jellyfishSettings 合并后的运行期设置，不可为 {@code null}
     * @param pluginRoots       插件扫描根目录，可为 {@code null}（按空处理）
     * @param systemAgent       内置默认 agent，可为 {@code null}
     * @return 不可变快照
     */
    public static RuntimeSnapshot of(ModelSettings modelSettings, AgentSettings agentSettings,
                                     JellyfishSettings jellyfishSettings, List<Path> pluginRoots,
                                     AgentDefinition systemAgent) {
        List<Provider> providers = new ArrayList<>(modelSettings.getProviders().values());
        return new RuntimeSnapshot(modelSettings, agentSettings, jellyfishSettings,
                Collections.unmodifiableList(providers),
                pluginRoots == null
                        ? Collections.<Path>emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(pluginRoots)),
                systemAgent);
    }

    /**
     * 获取模型配置。
     *
     * @return 模型配置，保证非 {@code null}
     */
    public ModelSettings getModelSettings() {
        return modelSettings;
    }

    /**
     * 获取 agent 配置。
     *
     * @return agent 配置，保证非 {@code null}
     */
    public AgentSettings getAgentSettings() {
        return agentSettings;
    }

    /**
     * 获取内置默认 agent。
     *
     * @return 内置默认 agent；未加载配置时为 {@code null}
     */
    public AgentDefinition getSystemAgent() {
        return systemAgent;
    }

    /**
     * 获取运行期设置。
     *
     * @return 运行期设置，保证非 {@code null}
     */
    public JellyfishSettings getJellyfishSettings() {
        return jellyfishSettings;
    }

    /**
     * 获取 provider 列表。
     *
     * @return provider 列表，可能为空但不会为 {@code null}
     */
    public List<Provider> getProviders() {
        return providers;
    }

    /**
     * 获取插件扫描根目录。
     *
     * @return 不可变路径列表，可能为空但不会为 {@code null}
     */
    public List<Path> getPluginRoots() {
        return pluginRoots;
    }
}
