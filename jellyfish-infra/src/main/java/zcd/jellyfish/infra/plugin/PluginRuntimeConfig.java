package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.infra.config.PluginsSettings;

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
 * 这是<b>内核内部</b>的配置载体，由两个来源组装：扫描目录来自 {@code config.json} 的
 * {@code plugins.roots}（已由 {@code RuntimeConfig} 展开 {@code ~} 并丢弃空白），
 * 启用 / 禁用名单与各插件配置段来自 {@code jellyfish.json} 的 {@code plugins} 段
 * （{@link PluginsSettings}）。插件管理器只认本对象，不感知配置层的读取与合并逻辑。
 * <p>
 * <b>引用稳定、快照可换</b>：本对象在构造期就被注入 {@code PF4JPluginManager} 与
 * {@code ReadOnlyTools}，而配置要到 {@code runtimeConfig.refresh()} 之后才可用（那时这些协作者
 * 早已构造完毕）。因此不靠「重建对象」发布新值，而是让 {@link #refresh(List, PluginsSettings)}
 * 整体替换内部那份不可变快照——与 {@code RuntimeConfig} + {@code RuntimeSnapshot} 同款做法。
 * <p>
 * 一句话记住使用方式：<b>它不是一个线程安全的可变配置对象，只是一个快照发布点</b>；
 * 写入口只有 {@link #refresh(List, PluginsSettings)} 一个。
 *
 * @author zcd
 */
public final class PluginRuntimeConfig {

    /** 默认插件根目录。 */
    public static final String DEFAULT_PLUGINS_ROOT = "plugins";

    /** 当前快照，整体替换保证读取一致性。 */
    private volatile Snapshot snapshot;

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
        this.snapshot = Snapshot.of(pluginsRoots, enabledPluginIds, disabledPluginIds, pluginConfigurations);
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
     * 用最新配置整体替换快照。
     * <p>
     * 必须在插件运行时 {@code bootstrap()} 之前调用：扫描根目录与启用 / 禁用种子都是那一刻读取的。
     * 空配置（两个入参均为 {@code null} 或等价的空值）等价于 {@link #defaults()}：
     * 扫描目录回退 {@link #DEFAULT_PLUGINS_ROOT}，名单与配置段置空。
     *
     * @param pluginsRoots 插件扫描根目录，为 {@code null} 或空时回退到 {@link #DEFAULT_PLUGINS_ROOT}
     * @param settings     合并后的插件段配置（名单 + 各插件配置段），可为 {@code null}
     */
    public void refresh(List<Path> pluginsRoots, PluginsSettings settings) {
        if (settings == null) {
            this.snapshot = Snapshot.of(pluginsRoots, null, null, null);
            return;
        }
        this.snapshot = Snapshot.of(pluginsRoots, new LinkedHashSet<>(settings.getEnabled()),
                new LinkedHashSet<>(settings.getDisabled()), settings.getConfigurations());
    }

    /**
     * 获取插件根目录。
     *
     * @return 不可变目录列表，至少一个元素
     */
    public List<Path> getPluginsRoots() {
        return snapshot.pluginsRoots;
    }

    /**
     * 获取启用名单。
     *
     * @return 不可变集合，为空表示不额外限定
     */
    public Set<String> getEnabledPluginIds() {
        return snapshot.enabledPluginIds;
    }

    /**
     * 获取禁用名单。
     *
     * @return 不可变集合
     */
    public Set<String> getDisabledPluginIds() {
        return snapshot.disabledPluginIds;
    }

    /**
     * 获取指定插件的配置段。
     *
     * @param pluginId 插件标识，可为 {@code null}
     * @return 不可变配置映射，未配置时为空映射而非 {@code null}
     */
    public Map<String, Object> configurationOf(String pluginId) {
        Map<String, Object> configuration = snapshot.pluginConfigurations.get(pluginId);
        return configuration == null ? Collections.<String, Object>emptyMap() : configuration;
    }

    /**
     * 获取全部插件配置段。
     * <p>
     * 供<b>不按 pluginId 逐个查询</b>的消费方使用：例如权限模块要把各插件声明的只读工具白名单
     * 合并成一张全局工具名集合，它并不关心「哪个插件声明的」。
     * <p>
     * 同一快照期内的返回值<b>恒为同一实例</b>：消费方可以用引用比较判断「快照是否换过」，
     * 从而避免每次调用都重新解析（见 {@code ReadOnlyTools}）。
     *
     * @return 不可变映射（pluginId → 该插件配置段），无配置时为空映射而非 {@code null}
     */
    public Map<String, Map<String, Object>> getPluginConfigurations() {
        return snapshot.pluginConfigurations;
    }

    /**
     * 内部不可变快照：四个字段一次性替换，读取方要么看到完整的新值、要么看到完整的旧值。
     *
     * @author zcd
     */
    private static final class Snapshot {

        /** 插件根目录，至少一个元素。 */
        private final List<Path> pluginsRoots;

        /** 启用名单，空表示不额外限定。 */
        private final Set<String> enabledPluginIds;

        /** 禁用名单。 */
        private final Set<String> disabledPluginIds;

        /** pluginId → 该插件配置段。 */
        private final Map<String, Map<String, Object>> pluginConfigurations;

        /**
         * 构造快照。
         *
         * @param pluginsRoots         插件根目录
         * @param enabledPluginIds     启用名单
         * @param disabledPluginIds    禁用名单
         * @param pluginConfigurations 插件配置段
         */
        private Snapshot(List<Path> pluginsRoots, Set<String> enabledPluginIds, Set<String> disabledPluginIds,
                         Map<String, Map<String, Object>> pluginConfigurations) {
            this.pluginsRoots = pluginsRoots;
            this.enabledPluginIds = enabledPluginIds;
            this.disabledPluginIds = disabledPluginIds;
            this.pluginConfigurations = pluginConfigurations;
        }

        /**
         * 构造不可变快照，并统一各段缺省语义。
         *
         * @param roots          插件根目录，为 {@code null} 或空时回退默认目录
         * @param enabled        启用名单，可为 {@code null}
         * @param disabled       禁用名单，可为 {@code null}
         * @param configurations 插件配置段，可为 {@code null}
         * @return 不可变快照
         */
        private static Snapshot of(List<Path> roots, Set<String> enabled, Set<String> disabled,
                                   Map<String, Map<String, Object>> configurations) {
            return new Snapshot(
                    roots == null || roots.isEmpty()
                            ? Collections.singletonList(Paths.get(DEFAULT_PLUGINS_ROOT))
                            : Collections.unmodifiableList(new ArrayList<>(roots)),
                    enabled == null
                            ? Collections.<String>emptySet()
                            : Collections.unmodifiableSet(new LinkedHashSet<>(enabled)),
                    disabled == null
                            ? Collections.<String>emptySet()
                            : Collections.unmodifiableSet(new LinkedHashSet<>(disabled)),
                    configurations == null
                            ? Collections.<String, Map<String, Object>>emptyMap()
                            : Collections.unmodifiableMap(new LinkedHashMap<>(configurations)));
        }
    }
}
