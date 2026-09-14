package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code jellyfish.json} 反序列化后的原始结构：运行期设置。
 * <p>
 * 按本仓库「类名 ↔ 文件名」的统一约定：{@code config.json} → {@link AppConfig}、
 * {@code models.json} → {@link ModelSettings}、{@code agents.json} → {@link AgentSettings}、
 * {@code jellyfish.json} → 本类。
 * <p>
 * 本类承载「既不属于模型、也不属于 agent」的运行期配置段：当前只有 {@code plugins}，
 * 将来的会话 / 事件 / 权限段也落在这里。插件段<b>不</b>塞进 {@link ModelSettings}：
 * 一个根类只承载自己那份文件的内容，这是「一个文件一个根类」约定的直接推论。
 * <p>
 * 不可变：不存在 setter，{@code plugins} 缺省为空对象而非 {@code null}。
 *
 * @author zcd
 */
public class JellyfishSettings {

    /** 插件段。 */
    private final PluginsSettings plugins;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param plugins 插件段，可为 {@code null}（按空处理）
     */
    @JsonCreator
    public JellyfishSettings(@JsonProperty("plugins") PluginsSettings plugins) {
        this.plugins = plugins == null ? new PluginsSettings(null, null, null, null) : plugins;
    }

    /**
     * 获取插件段。
     *
     * @return 插件段，保证非 {@code null}
     */
    public PluginsSettings getPlugins() {
        return plugins;
    }

    /**
     * 判断是否未配置任何运行期设置段。
     *
     * @return 所有段都为空返回 {@code true}
     */
    public boolean isEmpty() {
        return plugins.isEmpty();
    }
}
