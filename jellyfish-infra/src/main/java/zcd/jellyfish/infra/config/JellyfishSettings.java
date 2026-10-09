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
 * <b>「这一段没写」与「写了但全是缺省值」是两回事</b>：反序列化时字段缺失得到 {@code null}，
 * 而各段字段本身允许为 {@code null} 的只有这一处。五段的双源合并都按「这一段写没写」决定是否覆盖
 * （{@code react} / {@code permission} / {@code subAgent} / {@code ask} 是整对象覆盖，{@code plugins}
 * 段内再按键级声明逐项处理），因此每段各记一个「是否声明」的标记（见 {@link #isPluginsDeclared()}
 * 等）——否则一份只写了别的段的项目级文件会把全局级的整段配置顶成缺省值，而用户看不出任何痕迹。
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

    /** 本份文件里是否写了 {@code plugins} 段。 */
    private final boolean pluginsDeclared;

    /** 本份文件里是否写了 {@code react} 段。 */
    private final boolean reactDeclared;

    /** 本份文件里是否写了 {@code permission} 段。 */
    private final boolean permissionDeclared;

    /** 本份文件里是否写了 {@code subAgent} 段。 */
    private final boolean subAgentDeclared;

    /** 本份文件里是否写了 {@code ask} 段。 */
    private final boolean askDeclared;

    /**
     * 反序列化与合并共用的构造器。
     * <p>
     * 「是否声明」直接由入参是否为 {@code null} 判定：反序列化时字段缺失才会得到 {@code null}，
     * 而本仓库的配置读取路径之外的构造点（合并产物、测试）传的都是非空对象——它们之后不再参与
     * 双源合并，因此那两个标记在那些对象上没有意义。
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
        this.pluginsDeclared = plugins != null;
        this.reactDeclared = react != null;
        this.permissionDeclared = permission != null;
        this.subAgentDeclared = subAgent != null;
        this.askDeclared = ask != null;
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
     * 判断本份文件是否写了 {@code plugins} 段。
     * <p>
     * 只影响双源合并：项目级没写这一段时回退全局级那一份。段内的名单与各插件配置段另有
     * 各自的声明判定（见 {@link PluginsSettings#isEnabledDeclared()}），那两级互不替代。
     *
     * @return 配置里写了 {@code plugins} 段返回 {@code true}（哪怕其内容为空对象）
     */
    public boolean isPluginsDeclared() {
        return pluginsDeclared;
    }

    /**
     * 判断本份文件是否写了 {@code react} 段。
     * <p>
     * 只影响双源合并：项目级没写这一段时回退全局级那一份，而不是拿缺省值把全局级顶掉。
     *
     * @return 配置里写了 {@code react} 段返回 {@code true}（哪怕其内容与缺省值一致）
     */
    public boolean isReactDeclared() {
        return reactDeclared;
    }

    /**
     * 判断本份文件是否写了 {@code permission} 段。
     * <p>
     * 只影响双源合并，同 {@link #isReactDeclared()}。
     *
     * @return 配置里写了 {@code permission} 段返回 {@code true}
     */
    public boolean isPermissionDeclared() {
        return permissionDeclared;
    }

    /**
     * 判断本份文件是否写了 {@code subAgent} 段。
     * <p>
     * 只影响双源合并，同 {@link #isReactDeclared()}。
     *
     * @return 配置里写了 {@code subAgent} 段返回 {@code true}
     */
    public boolean isSubAgentDeclared() {
        return subAgentDeclared;
    }

    /**
     * 判断本份文件是否写了 {@code ask} 段。
     * <p>
     * 只影响双源合并，同 {@link #isReactDeclared()}。
     *
     * @return 配置里写了 {@code ask} 段返回 {@code true}
     */
    public boolean isAskDeclared() {
        return askDeclared;
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
