package zcd.jellyfish.infra.plugin;

import org.pf4j.PluginStatusProvider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置驱动的插件启用状态：禁用名单来自 {@code jellyfish.json}，不使用插件目录里的 {@code disabled.txt}。
 * <p>
 * 四个设计点：
 * <ol>
 *     <li><b>不使用 PF4J 默认实现的直接原因</b>：{@code DefaultPluginStatusProvider} 的
 *     {@code disablePlugin} / {@code enablePlugin} 会往插件目录写文件，而内核配置是只读的；
 *     本类把运行期开关留在内存里。</li>
 *     <li><b>配置只作初始种子</b>：运行期的启用 / 禁用不回写配置文件，因此重启后回到配置状态。</li>
 *     <li><b>运行期开关优先于配置</b>：显式启用能覆盖「不在启用名单内」这一判定，
 *     否则用户在运行期执行「启用」将永远不会生效。</li>
 *     <li><b>配置重载以配置为权威</b>：{@link #attach(PluginRuntimeConfig)} 整体替换配置快照，
 *     同时清空运行期开关——「重载」的语义就是「回到配置说的样子」；若保留旧开关，
 *     用户改了名单却看到插件状态照旧，会变成查不出来的谜。</li>
 * </ol>
 * <b>启用名单「未声明」与「声明为空」不是一回事</b>：未声明表示不额外限定（全部启用），
 * 声明为空表示一个都不启用。两者若归一成同一个空集合，{@code "enabled": []} 会把全部插件放进来。
 * <p>
 * 配置以<b>不可变快照</b>承载、整体替换发布（{@link #attach} 可在运行期被配置重载线程调用），
 * 因此读路径无需加锁；运行期开关用并发集合。
 *
 * @author zcd
 */
public final class ConfigPluginStatusProvider implements PluginStatusProvider {

    /** 当前配置快照，整体替换保证读一致性。 */
    private volatile Configured configured = Configured.EMPTY;

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
     * 以配置重建状态：整体替换配置快照，并清空运行期开关。
     * <p>
     * 启动期与配置重载期走同一条路径：重载的唯一语义就是「重新按配置说话」。
     *
     * @param config 装配输入，不可为 {@code null}
     */
    void attach(PluginRuntimeConfig config) {
        configured = new Configured(config.getEnabledPluginIds(), config.isEnabledPluginIdsDeclared(),
                config.getDisabledPluginIds());
        runtimeEnabled.clear();
        runtimeDisabled.clear();
    }

    @Override
    public boolean isPluginDisabled(String pluginId) {
        if (runtimeEnabled.contains(pluginId)) {
            return false;
        }
        if (runtimeDisabled.contains(pluginId)) {
            return true;
        }
        // 只读一次快照：判定过程中配置被重载也不会出现「启用名单读的是新的、禁用名单读的是旧的」
        Configured current = configured;
        if (current.disabled.contains(pluginId)) {
            return true;
        }
        // 声明了启用名单就是一个白名单（空名单 → 全部禁用）；未声明才是不额外限定
        return current.enabledDeclared && !current.enabled.contains(pluginId);
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

    /**
     * 不可变配置快照：启用名单、是否声明、禁用名单一次性替换。
     *
     * @author zcd
     */
    private static final class Configured {

        /** 空快照：未声明启用名单，两个名单皆空。 */
        private static final Configured EMPTY = new Configured(Collections.<String>emptySet(), false,
                Collections.<String>emptySet());

        /** 启用名单。 */
        private final Set<String> enabled;

        /** 启用名单是否被显式声明。 */
        private final boolean enabledDeclared;

        /** 禁用名单。 */
        private final Set<String> disabled;

        /**
         * 构造快照。
         *
         * @param enabled         启用名单
         * @param enabledDeclared 是否显式声明
         * @param disabled        禁用名单
         */
        private Configured(Set<String> enabled, boolean enabledDeclared, Set<String> disabled) {
            this.enabled = Collections.unmodifiableSet(new LinkedHashSet<String>(enabled));
            this.enabledDeclared = enabledDeclared;
            this.disabled = Collections.unmodifiableSet(new LinkedHashSet<String>(disabled));
        }
    }
}
