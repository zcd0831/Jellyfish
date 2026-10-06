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
 * 本类承载「既不属于模型、也不属于 agent」的运行期配置段：当前有 {@code plugins}、{@code react}、
 * {@code permission}、{@code subAgent} 与 {@code ask}，将来的会话 / 事件段也落在这里。各段<b>不</b>塞进 {@link ModelSettings}：
 * 一个根类只承载自己那份文件的内容，这是「一个文件一个根类」约定的直接推论。
 * <p>
 * 不可变：不存在 setter，{@code plugins} / {@code react} / {@code permission} / {@code subAgent}
 * / {@code ask} 缺省均为空对象或缺省值而非 {@code null}。
 *
 * @author zcd
 */
public class JellyfishSettings {

    /** 插件段。 */
    private final PluginsSettings plugins;

    /** ReAct 循环段。 */
    private final ReactSettings react;

    /** 权限段。 */
    private final PermissionApprovalSettings permission;

    /** 子代理委派段。 */
    private final SubAgentSettings subAgent;

    /** 向用户提问段。 */
    private final AskSettings ask;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param plugins    插件段，可为 {@code null}（按空处理）
     * @param react      ReAct 循环段，可为 {@code null}（按缺省值处理）
     * @param permission 权限段，可为 {@code null}（按缺省值处理）
     * @param subAgent   子代理委派段，可为 {@code null}（按缺省值处理）
     * @param ask        向用户提问段，可为 {@code null}（按缺省值处理）
     */
    @JsonCreator
    public JellyfishSettings(@JsonProperty("plugins") PluginsSettings plugins,
                             @JsonProperty("react") ReactSettings react,
                             @JsonProperty("permission") PermissionApprovalSettings permission,
                             @JsonProperty("subAgent") SubAgentSettings subAgent,
                             @JsonProperty("ask") AskSettings ask) {
        this.plugins = plugins == null ? new PluginsSettings(null, null, null) : plugins;
        this.react = react == null ? new ReactSettings() : react;
        this.permission = permission == null ? new PermissionApprovalSettings() : permission;
        this.subAgent = subAgent == null ? new SubAgentSettings() : subAgent;
        this.ask = ask == null ? new AskSettings() : ask;
    }

    /**
     * 便捷构造器：不带提问段。
     * <p>
     * 保留它是为了源码兼容：绝大多数构造点（测试与内部工具）只关心前四段。
     *
     * @param plugins    插件段，可为 {@code null}（按空处理）
     * @param react      ReAct 循环段，可为 {@code null}（按缺省值处理）
     * @param permission 权限段，可为 {@code null}（按缺省值处理）
     * @param subAgent   子代理委派段，可为 {@code null}（按缺省值处理）
     */
    public JellyfishSettings(PluginsSettings plugins, ReactSettings react,
                             PermissionApprovalSettings permission, SubAgentSettings subAgent) {
        this(plugins, react, permission, subAgent, null);
    }

    /**
     * 便捷构造器：不带子代理段。
     * <p>
     * 保留它是为了源码兼容：绝大多数构造点（测试与内部工具）只关心前三段。
     *
     * @param plugins    插件段，可为 {@code null}（按空处理）
     * @param react      ReAct 循环段，可为 {@code null}（按缺省值处理）
     * @param permission 权限段，可为 {@code null}（按缺省值处理）
     */
    public JellyfishSettings(PluginsSettings plugins, ReactSettings react,
                             PermissionApprovalSettings permission) {
        this(plugins, react, permission, null);
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
     * 获取 ReAct 循环段。
     *
     * @return ReAct 循环段，保证非 {@code null}
     */
    public ReactSettings getReact() {
        return react;
    }

    /**
     * 获取权限段。
     *
     * @return 权限段，保证非 {@code null}
     */
    public PermissionApprovalSettings getPermission() {
        return permission;
    }

    /**
     * 获取子代理委派段。
     *
     * @return 子代理委派段，保证非 {@code null}
     */
    public SubAgentSettings getSubAgent() {
        return subAgent;
    }

    /**
     * 获取向用户提问段。
     *
     * @return 向用户提问段，保证非 {@code null}
     */
    public AskSettings getAsk() {
        return ask;
    }

    /**
     * 判断是否未配置任何运行期设置段。
     *
     * @return 五段全为缺省值返回 {@code true}
     */
    public boolean isEmpty() {
        return plugins.isEmpty() && react.isDefault() && permission.isDefault() && subAgent.isDefault()
                && ask.isDefault();
    }
}
