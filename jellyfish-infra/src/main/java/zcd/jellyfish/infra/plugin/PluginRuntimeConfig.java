package zcd.jellyfish.infra.plugin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件运行时的装配输入：插件根目录、启用 / 禁用名单、各插件配置段。
 * <p>
 * 这是<b>内核内部</b>的配置载体（用户写在 {@code jellyfish.json} 的 {@code plugins} 段，
 * 由配置层负责双源合并后组装成本对象），因此以 {@code Config} 结尾；
 * 插件管理器只认本对象，不依赖配置层，便于单测直接构造。
 * <p>
 * 不可变：集合在构造时复制并包装，装配完成后不会被外部改动。
 *
 * @author zcd
 */
public final class PluginRuntimeConfig {

    /** 默认插件根目录。 */
    public static final String DEFAULT_PLUGINS_ROOT = "plugins";

    /** 插件根目录，顺序即扫描顺序。 */
    private final List<Path> pluginsRoots;

    /** 启用名单，空表示不额外限定。 */
    private final Set<String> enabledPluginIds;

    /** 禁用名单，优先于启用名单。 */
    private final Set<String> disabledPluginIds;

    /** pluginId → 该插件配置段。 */
    private final Map<String, Map<String, Object>> pluginConfigurations;

    /**
     * 构造装配输入。
     *
     * @param pluginsRoots         插件根目录，为 {@code null} 或空时回退到 {@link #DEFAULT_PLUGINS_ROOT}
     * @param enabledPluginIds     启用名单，可为 {@code null}
     * @param disabledPluginIds    禁用名单，可为 {@code null}
     * @param pluginConfigurations 插件配置段，可为 {@code null}
     */
    public PluginRuntimeConfig(List<Path> pluginsRoots, Set<String> enabledPluginIds,
                               Set<String> disabledPluginIds,
                               Map<String, Map<String, Object>> pluginConfigurations) {
        this.pluginsRoots = pluginsRoots == null || pluginsRoots.isEmpty()
                ? Collections.singletonList(Paths.get(DEFAULT_PLUGINS_ROOT))
                : Collections.unmodifiableList(new ArrayList<>(pluginsRoots));
        this.enabledPluginIds = enabledPluginIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(enabledPluginIds));
        this.disabledPluginIds = disabledPluginIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(disabledPluginIds));
        this.pluginConfigurations = pluginConfigurations == null
                ? Collections.<String, Map<String, Object>>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pluginConfigurations));
    }

    /**
     * 构造全默认的装配输入：扫描默认插件目录、不限启用禁用、无插件配置。
     *
     * @return 装配输入
     */
    public static PluginRuntimeConfig defaults() {
        return new PluginRuntimeConfig(null, null, null, null);
    }

    /**
     * 构造只指定插件根目录的装配输入，便于测试。
     *
     * @param roots 插件根目录
     * @return 装配输入
     */
    public static PluginRuntimeConfig ofRoots(Path... roots) {
        List<Path> list = new ArrayList<>();
        if (roots != null) {
            for (Path root : roots) {
                list.add(root);
            }
        }
        return new PluginRuntimeConfig(list, null, null, null);
    }

    /**
     * 获取插件根目录。
     *
     * @return 不可变目录列表，至少一个元素
     */
    public List<Path> getPluginsRoots() {
        return pluginsRoots;
    }

    /**
     * 获取启用名单。
     *
     * @return 不可变集合，为空表示不额外限定
     */
    public Set<String> getEnabledPluginIds() {
        return enabledPluginIds;
    }

    /**
     * 获取禁用名单。
     *
     * @return 不可变集合
     */
    public Set<String> getDisabledPluginIds() {
        return disabledPluginIds;
    }

    /**
     * 获取指定插件的配置段。
     *
     * @param pluginId 插件标识，可为 {@code null}
     * @return 不可变配置映射，未配置时为空映射而非 {@code null}
     */
    public Map<String, Object> configurationOf(String pluginId) {
        Map<String, Object> configuration = pluginConfigurations.get(pluginId);
        return configuration == null ? Collections.<String, Object>emptyMap() : configuration;
    }

    /**
     * 获取全部插件配置段。
     * <p>
     * 供<b>不按 pluginId 逐个查询</b>的消费方使用：例如权限模块要把各插件声明的只读工具白名单
     * 合并成一张全局工具名集合，它并不关心「哪个插件声明的」。
     *
     * @return 不可变映射（pluginId → 该插件配置段），无配置时为空映射而非 {@code null}
     */
    public Map<String, Map<String, Object>> getPluginConfigurations() {
        return pluginConfigurations;
    }
}
