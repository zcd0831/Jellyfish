package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

/**
 * 应用级配置，对应 {@code classpath:config.json} 的内容。
 * <p>
 * 文件顶层的每一项都是应用级配置，按其值类型选择字段类型即可，例如：
 * <pre>
 * {
 *   "processName": "Jellyfish",
 *   "model":     { "globalPath": "/etc/jellyfish/models.json",   "projectPath": "./models.json" },
 *   "agent":     { "globalPath": "/etc/jellyfish/agents.json",   "projectPath": "./agents.json" },
 *   "jellyfish": { "globalPath": "/etc/jellyfish/jellyfish.json", "projectPath": "./jellyfish.json" },
 *   "plugins":   { "roots": ["plugins", "~/jellyfish/plugins"] }
 * }
 * </pre>
 * 标量配置声明为普通字段；需要「全局级 + 项目级」双源合并的配置段落声明为
 * {@link ConfigPaths}，交由 {@link RuntimeConfig} 统一按双源规则读取与合并；插件扫描目录这类目录清单
 * 声明为 {@link PluginPaths}。三者只是字段类型不同，不存在特殊类别；新增配置项即新增字段。
 * <p>
 * <b>本配置声明「一个文件在哪」与「一个目录在哪」</b>：每份配置文件对应一个配置类
 * （{@code models.json} → {@link ModelSettings}、{@code agents.json} → {@link AgentSettings}、
 * {@code jellyfish.json} → {@link JellyfishSettings}），路径全部写在这里；插件扫描目录同理。
 * 扫描目录之所以不放在 {@code jellyfish.json}，是因为它与「去哪个文件读配置」属于同一类部署事实，
 * 放同一处才不用两处找。
 * <p>
 * 纯数据类：只负责反序列化与取值，不承担任何文件读取（读取由 {@link ConfigLoader} 完成）。
 * 类中只声明已知配置项，未声明的顶层配置项会被反序列化直接忽略。
 *
 * @author zcd
 */
public class AppConfig {

    /** 应用配置在 classpath 中的固定路径。 */
    public static final String CONFIG_PATH = "classpath:config.json";

    /** {@code processName} 的缺省值。 */
    public static final String DEFAULT_PROCESS_NAME = "Jellyfish";

    /** 进程名。 */
    private final String processName;

    /** 模型配置段的双源文件路径。 */
    private final ConfigPaths model;

    /** agent 配置段的双源文件路径。 */
    private final ConfigPaths agent;

    /** 运行期设置段的双源文件路径。 */
    private final ConfigPaths jellyfish;

    /** 插件扫描根目录。 */
    private final PluginPaths plugins;

    /**
     * 反序列化使用的构造器，由 {@link JsonCreator} 接管。
     *
     * @param processName 进程名，可为 {@code null} 或空白
     * @param model       模型配置段的双源路径，可为 {@code null}
     * @param agent       agent 配置段的双源路径，可为 {@code null}
     * @param jellyfish   运行期设置段的双源路径，可为 {@code null}
     * @param plugins     插件扫描根目录，可为 {@code null}
     */
    @JsonCreator
    public AppConfig(@JsonProperty("processName") String processName,
                     @JsonProperty("model") ConfigPaths model,
                     @JsonProperty("agent") ConfigPaths agent,
                     @JsonProperty("jellyfish") ConfigPaths jellyfish,
                     @JsonProperty("plugins") PluginPaths plugins) {
        this.processName = StringUtils.isBlank(processName) ? DEFAULT_PROCESS_NAME : processName;
        this.model = model == null ? new ConfigPaths() : model;
        this.agent = agent == null ? new ConfigPaths() : agent;
        this.jellyfish = jellyfish == null ? new ConfigPaths() : jellyfish;
        this.plugins = plugins == null ? new PluginPaths(null) : plugins;
    }

    /**
     * 获取进程名。
     *
     * @return 进程名；未配置时返回缺省值 {@link #DEFAULT_PROCESS_NAME}
     */
    public String getProcessName() {
        return processName;
    }

    /**
     * 获取模型配置段的双源路径。
     *
     * @return 模型配置段的双源路径，未配置时为空路径对象而非 {@code null}
     */
    public ConfigPaths getModel() {
        return model;
    }

    /**
     * 获取 agent 配置段的双源路径。
     *
     * @return agent 配置段的双源路径，未配置时为空路径对象而非 {@code null}
     */
    public ConfigPaths getAgent() {
        return agent;
    }

    /**
     * 获取运行期设置段的双源路径。
     *
     * @return 运行期设置段的双源路径，未配置时为空路径对象而非 {@code null}
     */
    public ConfigPaths getJellyfish() {
        return jellyfish;
    }

    /**
     * 获取插件扫描根目录。
     *
     * @return 插件扫描根目录，未配置时为空对象而非 {@code null}
     */
    public PluginPaths getPlugins() {
        return plugins;
    }
}
