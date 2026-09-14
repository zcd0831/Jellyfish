package zcd.jellyfish.infra.plugin;

/**
 * 插件描述符的键名常量。
 * <p>
 * 分两组：{@code plugin.*} 是 PF4J 原生键，{@code jellyfish.*} 是本项目的私有键。
 * 私有键统一加前缀，既避免与 PF4J 保留键冲突，也保证未来 PF4J 新增键不会撞车。

 * @author zcd
 */
public final class PluginProperties {

    /** 描述符文件名，PF4J 按该名称在每个插件根下查找。 */
    public static final String FILE_NAME = "plugin.properties";

    /** 插件唯一标识，同时作为注册表的 owner。 */
    public static final String PLUGIN_ID = "plugin.id";

    /** 插件入口类：{@code JellyfishPlugin} 实现类，不是 {@code org.pf4j.Plugin} 子类。 */
    public static final String PLUGIN_CLASS = "plugin.class";

    /** 插件版本，参与依赖解析。 */
    public static final String PLUGIN_VERSION = "plugin.version";

    /** 内核兼容版本约束（semver 表达式），由 PF4J 的 {@code VersionManager} 在加载期校验。 */
    public static final String PLUGIN_REQUIRES = "plugin.requires";

    /** 插件标签，仅作诊断与展示用途；agent 授权只按工具名，不做按标签授权。 */
    public static final String JELLYFISH_TAGS = "jellyfish.tags";

    /**
     * 常量类，禁止实例化。
     */
    private PluginProperties() {
    }
}
