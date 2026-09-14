package zcd.jellyfish.infra.plugin;

import org.pf4j.PluginStatusProvider;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置驱动的插件启用状态：禁用名单来自 {@code jellyfish.json}，不使用插件目录里的 {@code disabled.txt}。
 * <p>
 * 三个设计点：
 * <ol>
 *     <li><b>不使用 PF4J 默认实现的直接原因</b>：{@code DefaultPluginStatusProvider} 的
 *     {@code disablePlugin} / {@code enablePlugin} 会往插件目录写文件，而内核配置是只读的；
 *     本类把运行期开关留在内存里。</li>
 *     <li><b>配置只作初始种子</b>：运行期的启用 / 禁用不回写配置文件，因此重启后回到配置状态。</li>
 *     <li><b>运行期开关优先于配置</b>：显式启用能覆盖「不在启用名单内」这一判定，
 *     否则用户在运行期执行「启用」将永远不会生效。</li>
 * </ol>
 * 判定与运行期开关分别用并发集合与构造后不再修改的普通集合承载，读路径无需加锁。
 *
 * @author zcd
 */
public final class ConfigPluginStatusProvider implements PluginStatusProvider {

    /** 配置中的启用名单（空表示不额外限定），构造后不再修改。 */
    private final Set<String> configuredEnabled = new LinkedHashSet<>();

    /** 配置中的禁用名单，构造后不再修改。 */
    private final Set<String> configuredDisabled = new LinkedHashSet<>();

    /** 运行期显式启用的插件。 */
    private final Set<String> runtimeEnabled = ConcurrentHashMap.newKeySet();

    /** 运行期显式禁用的插件。 */
    private final Set<String> runtimeDisabled = ConcurrentHashMap.newKeySet();

    /**
     * 构造空状态提供者，配置由 {@link #attach(PluginRuntimeConfig)} 后续注入。
     * <p>
     * 之所以允许「无参构造 + 后置注入」：父类构造器内部就会调用
     * {@code createPluginStatusProvider()}，那时管理器的配置字段尚未赋值。
     */
    public ConfigPluginStatusProvider() {
    }

    /**
     * 以配置构造，便于单元测试直接使用。
     *
     * @param config 装配输入，不可为 {@code null}
     */
    public ConfigPluginStatusProvider(PluginRuntimeConfig config) {
        attach(config);
    }

    /**
     * 注入配置种子。
     *
     * @param config 装配输入，不可为 {@code null}
     */
    void attach(PluginRuntimeConfig config) {
        configuredEnabled.clear();
        configuredEnabled.addAll(config.getEnabledPluginIds());
        configuredDisabled.clear();
        configuredDisabled.addAll(config.getDisabledPluginIds());
    }

    @Override
    public boolean isPluginDisabled(String pluginId) {
        if (runtimeEnabled.contains(pluginId)) {
            return false;
        }
        if (runtimeDisabled.contains(pluginId)) {
            return true;
        }
        if (configuredDisabled.contains(pluginId)) {
            return true;
        }
        return !configuredEnabled.isEmpty() && !configuredEnabled.contains(pluginId);
    }

    @Override
    public void disablePlugin(String pluginId) {
        runtimeEnabled.remove(pluginId);
        runtimeDisabled.add(pluginId);
    }

    @Override
    public void enablePlugin(String pluginId) {
        runtimeDisabled.remove(pluginId);
        runtimeEnabled.add(pluginId);
    }
}
