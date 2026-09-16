package zcd.jellyfish.infra.config;

import java.util.Objects;

import zcd.jellyfish.infra.plugin.PluginReloadReport;

/**
 * 配置重载结果：耗时 + 插件侧实际发生的变动。
 * <p>
 * 只承载机器可读的事实，不承载给用户看的文案——「该对用户说什么」是命令层的措辞问题，
 * 与压缩不可用的提示同口径。
 *
 * @author zcd
 */
public final class ReloadOutcome {

    /** 重载耗时（毫秒）。 */
    private final long durationMillis;

    /** 插件侧变动报告，不会为 {@code null}。 */
    private final PluginReloadReport pluginReport;

    /**
     * 构造重载结果。
     *
     * @param durationMillis 重载耗时（毫秒）
     * @param pluginReport   插件侧变动报告，不可为 {@code null}
     */
    public ReloadOutcome(long durationMillis, PluginReloadReport pluginReport) {
        this.durationMillis = durationMillis;
        this.pluginReport = Objects.requireNonNull(pluginReport, "pluginReport must not be null");
    }

    /**
     * 获取重载耗时。
     *
     * @return 耗时（毫秒）
     */
    public long getDurationMillis() {
        return durationMillis;
    }

    /**
     * 获取插件侧变动报告。
     *
     * @return 报告，保证非 {@code null}
     */
    public PluginReloadReport getPluginReport() {
        return pluginReport;
    }
}
