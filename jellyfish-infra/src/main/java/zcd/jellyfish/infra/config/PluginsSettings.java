package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * @author zcd
 */
public class PluginsSettings {

    /** 启用名单，空表示不额外限定。 */
    private final List<String> enabled;

    /** 禁用名单，优先于启用名单。 */
    private final List<String> disabled;

    /** pluginId → 该插件配置段。 */
    private final Map<String, Map<String, Object>> configurations;

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
        this.enabled = copyOf(enabled);
        this.disabled = copyOf(disabled);
        this.configurations = configurations == null
                ? Collections.<String, Map<String, Object>>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configurations));
    }

    /**
     * 获取启用名单。
     *
     * @return 不可修改列表，为空表示不额外限定
     */
    public List<String> getEnabled() {
        return enabled;
    }

    /**
     * 获取禁用名单。
     *
     * @return 不可修改列表
     */
    public List<String> getDisabled() {
        return disabled;
    }

    /**
     * 获取各插件配置段。
     *
     * @return 不可变映射（pluginId → 配置段），无配置时为空映射而非 {@code null}
     */
    public Map<String, Map<String, Object>> getConfigurations() {
        return configurations;
    }

    /**
     * 判断是否未配置任何插件设置。
     *
     * @return 三段都为空返回 {@code true}
     */
    public boolean isEmpty() {
        return enabled.isEmpty() && disabled.isEmpty() && configurations.isEmpty();
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
}
