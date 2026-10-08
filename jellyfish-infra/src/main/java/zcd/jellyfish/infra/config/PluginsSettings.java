package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jellyfish.json} 的 {@code plugins} 段：启用 / 禁用名单与各插件配置段。
 * <p>
 * 这是<b>用户可见</b>的配置结构（以 {@code Settings} 结尾），只承载单份文件的内容；
 * 内核内部那份「插件管理器真正认的」不可变装配输入是 {@code PluginRuntimeConfig}，
 * 由装配根在两份配置合并后组装，因此插件运行时不需要认识本类之外的配置类型。
 * <p>
 * <b>本段不含扫描目录</b>：扫描目录是「内核去哪找插件 jar」的部署事实，与 {@code models.json} /
 * {@code agents.json} 的文件路径同性质，因此写在 {@code config.json}（{@link PluginPaths}）；
 * 本段只承载「加载之后怎么用」的运行期设置。这也让 {@code jellyfish.json} 的插件段不再混有
 * 「路径」与「开关」两种语义。
 * <p>
 * 两个保留键（{@code enabled} / {@code disabled}）与各插件配置段刻意分开放：
 * {@code configurations.<pluginId>}。插件标识由插件作者自由取名，若与保留键同级，
 * 撞名时既没有报错也没有优先级约定。
 * <p>
 * 不可变：列表在构造时复制并包装，{@code configurations} 以不可变映射发布（内层映射沿用原引用，
 * 与 {@code PluginRuntimeConfig} 的处理保持一致）。
 * <p>
 * <b>「未声明」与「声明为空」是两回事</b>：反序列化时字段缺失得到 {@code null}，显式写 {@code []}
 * 得到空列表。启用名单未声明表示「不额外限定」，声明为空则表示「一个都不启用」；若把两者一起归一
 * 成空列表，{@code "enabled": []} 会被读成「未配置」而把全部插件加载进来。因此两份名单各记一个
 * 「是否声明」的标记（见 {@link #isEnabledDeclared()} / {@link #isDisabledDeclared()}），
 * 双源合并也按该标记决定「项目级是否覆盖全局级」。
 *
 * @author zcd
 */
public class PluginsSettings {

    /** 启用名单，未声明表示不额外限定，声明为空表示一个都不启用。 */
    private final List<String> enabled;

    /** 禁用名单，未声明与声明为空等价：都不禁用任何插件。 */
    private final List<String> disabled;

    /** 启用名单是否被显式声明。 */
    private final boolean enabledDeclared;

    /** 禁用名单是否被显式声明。 */
    private final boolean disabledDeclared;

    /** pluginId → 该插件配置段（两级合并后的最终值）。 */
    private final Map<String, Map<String, Object>> configurations;

    /**
     * pluginId → <b>只由全局级决定</b>的那份配置段。
     * <p>
     * <b>为什么合并后还要留着全局级那一份</b>：同名插件段是整对象替换，因此项目级一旦写了这个
     * 插件段，{@link #configurations} 里就是项目级那份。而有一类键（提示内联上限、加载目录范围）
     * 只能认全局级那一份——同时也不能把全局级设的值一起丢掉（项目级可能只是加了个无关的键）。
     * <p>
     * 单份文件（未经合并）反序列化时，本字段与 {@link #configurations} 指向同一份内容：
     * 那个语境下「配置段」与「全局级配置段」本来就是一回事。
     */
    private final Map<String, Map<String, Object>> globalConfigurations;

    /** 哪些 pluginId 的配置段来自项目级（仅合并时能判定，单份文件时为空集）。 */
    private final Set<String> projectDeclaredPluginIds;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param enabled        启用名单，可为 {@code null}
     * @param disabled       禁用名单，可为 {@code null}
     * @param configurations pluginId 到插件配置段的映射，可为 {@code null}
     */
    @JsonCreator
    public PluginsSettings(@JsonProperty("enabled") List<String> enabled,
                           @JsonProperty("disabled") List<String> disabled,
                           @JsonProperty("configurations") Map<String, Map<String, Object>> configurations) {
        this(enabled, disabled, configurations, configurations, null);
    }

    /**
     * 合并结果专用构造器。
     * <p>
     * <b>刻意不带 {@code @JsonCreator}</b>：来源与「全局级那一份」都只能由合并产出。
     * 若它们能从 JSON 反序列化，一份项目级配置就能自己声明「我是全局级的」——
     * 那正是这条机制要挡的事。
     * <p>
     * 可见性为 {@code public} 而不是包私有：合并发生在 {@code RuntimeConfig} 的私有方法里，
     * 而「来源判定」这件事需要能单独构造出合并产物来断言。
     *
     * @param enabled                启用名单，可为 {@code null}
     * @param disabled               禁用名单，可为 {@code null}
     * @param configurations         pluginId 到插件配置段（合并后最终值）的映射，可为 {@code null}
     * @param globalConfigurations   pluginId 到全局级配置段的映射，可为 {@code null}
     * @param projectDeclaredPluginIds 来自项目级的 pluginId，可为 {@code null}
     */
    public PluginsSettings(List<String> enabled, List<String> disabled,
                           Map<String, Map<String, Object>> configurations,
                           Map<String, Map<String, Object>> globalConfigurations,
                           Set<String> projectDeclaredPluginIds) {
        this.enabled = copyOf(enabled);
        this.disabled = copyOf(disabled);
        this.enabledDeclared = enabled != null;
        this.disabledDeclared = disabled != null;
        this.configurations = copyOfConfigurations(configurations);
        this.globalConfigurations = globalConfigurations == null || globalConfigurations == configurations
                ? this.configurations
                : copyOfConfigurations(globalConfigurations);
        this.projectDeclaredPluginIds = projectDeclaredPluginIds == null || projectDeclaredPluginIds.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(projectDeclaredPluginIds));
    }

    /**
     * 获取启用名单。
     *
     * @return 不可修改列表；未声明时为空列表，语义由 {@link #isEnabledDeclared()} 区分
     */
    public List<String> getEnabled() {
        return enabled;
    }

    /**
     * 获取禁用名单。
     * <p>
     * 禁用名单为空与未声明等价（都不禁用任何插件），因此不需要额外区分。
     *
     * @return 不可修改列表
     */
    public List<String> getDisabled() {
        return disabled;
    }

    /**
     * 判断启用名单是否被显式声明。
     * <p>
     * 未声明表示不额外限定；声明为空表示一个都不启用——两者必须能区分，否则
     * {@code "enabled": []} 会被当成「未配置」而把全部插件加载进来。
     *
     * @return 配置里写了 {@code enabled} 字段（哪怕是空数组）返回 {@code true}
     */
    public boolean isEnabledDeclared() {
        return enabledDeclared;
    }

    /**
     * 判断禁用名单是否被显式声明。
     * <p>
     * 只影响双源合并：「项目级写了 {@code disabled: []}」应当清空全局级的禁用名单，而不是回退它。
     *
     * @return 配置里写了 {@code disabled} 字段（哪怕是空数组）返回 {@code true}
     */
    public boolean isDisabledDeclared() {
        return disabledDeclared;
    }

    /**
     * 获取各插件配置段（两级合并后的最终值）。
     *
     * @return 不可变映射（pluginId → 配置段），无配置时为空映射而非 {@code null}
     */
    public Map<String, Map<String, Object>> getConfigurations() {
        return configurations;
    }

    /**
     * 获取各插件配置段里<b>只由全局级决定</b>的那一份。
     * <p>
     * 未经合并（直接从单份文件反序列化）时与 {@link #getConfigurations()} 是同一份内容。
     *
     * @return 不可变映射（pluginId → 全局级配置段），无配置时为空映射而非 {@code null}
     */
    public Map<String, Map<String, Object>> getGlobalConfigurations() {
        return globalConfigurations;
    }

    /**
     * 判断某个插件的配置段是否来自项目级。
     *
     * @param pluginId 插件标识，可为 {@code null}
     * @return 项目级声明了该插件段返回 {@code true}
     */
    public boolean isProjectDeclared(String pluginId) {
        return pluginId != null && projectDeclaredPluginIds.contains(pluginId);
    }

    /**
     * 判断是否未配置任何插件设置。
     *
     * @return 三段都没写返回 {@code true}
     */
    public boolean isEmpty() {
        return !enabledDeclared && !disabledDeclared && configurations.isEmpty();
    }

    /**
     * 复制字符串列表并包装为不可修改列表。
     *
     * @param values 字符串列表，可为 {@code null}
     * @return 不可修改列表，入参为空时返回空列表
     */
    private static List<String> copyOf(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 复制「pluginId → 配置段」映射并包装为不可修改映射。
     * <p>
     * 与 {@code PluginRuntimeConfig} 的处理保持一致：内层配置段沿用原引用（它们来自 Jackson 反序列化，
     * 调用方拿不到可写句柄），外层复制以防调用方之后改动自己的映射。
     *
     * @param values 映射，可为 {@code null}
     * @return 不可修改映射，入参为空时返回空映射
     */
    private static Map<String, Map<String, Object>> copyOfConfigurations(
            Map<String, Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
