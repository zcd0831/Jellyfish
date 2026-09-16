package zcd.jellyfish.infra.metrics;

import java.util.Objects;

import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;

import zcd.jellyfish.infra.plugin.PF4JPluginManager;

/**
 * 插件健康检查：报告「发现 / 启动」的数量。
 * <p>
 * 一个插件都没装是 {@code WARN} 而不是 {@code DOWN}：内核自身可用，只是能力被削减
 * （没有工具插件时模型调不到任何工具）。真正的故障（插件运行时未启动）才判 {@code DOWN}。
 *
 * @author zcd
 */
public final class PluginHealthIndicator implements HealthIndicator {

    /** 检查项名称。 */
    private static final String NAME = "plugin";

    /** 插件运行时门面。 */
    private final PF4JPluginManager pluginManager;

    /**
     * 构造检查项。
     *
     * @param pluginManager 插件运行时门面，不可为 {@code null}
     */
    public PluginHealthIndicator(PF4JPluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager must not be null");
    }

    @Override
    public HealthResult check() {
        int total = 0;
        int started = 0;
        for (PluginWrapper wrapper : pluginManager.plugins()) {
            total++;
            PluginState state = pluginManager.stateOf(wrapper.getPluginId());
            if (state != null && state.isStarted()) {
                started++;
            }
        }
        if (total == 0) {
            return new HealthResult(NAME, HealthLevel.WARN, "未发现任何插件");
        }
        String detail = "started=" + started + "/" + total;
        return new HealthResult(NAME, started == 0 ? HealthLevel.WARN : HealthLevel.UP, detail);
    }
}
