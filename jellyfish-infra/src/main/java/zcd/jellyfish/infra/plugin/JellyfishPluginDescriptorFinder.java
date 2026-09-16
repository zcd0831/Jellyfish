package zcd.jellyfish.infra.plugin;

import org.apache.commons.lang3.StringUtils;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.PluginDescriptor;
import org.pf4j.PropertiesPluginDescriptorFinder;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;

import java.nio.file.Path;
import java.util.Properties;

/**
 * 插件描述符解析器：在 PF4J 的属性描述符之上读取 {@code jellyfish.*} 私有键。
 * <p>
 * 两处刻意的加固，都源于实测到的 PF4J 行为：
 * <ol>
 *     <li><b>{@code plugin.class} 校验「键存在」而不是「值非空」</b>：{@link DefaultPluginDescriptor}
 *     的 {@code pluginClass} 字段默认值是 {@code org.pf4j.Plugin}，而父类只在属性非空时才覆盖它；
 *     若按「值非空」校验，漏写该键会被放过去，最终报出「{@code org.pf4j.Plugin} 未实现
 *     JellyfishPlugin」这种指错方向的错误。</li>
 *     <li><b>{@code plugin.requires} 预检并归一</b>：该键是 semver 表达式，非法表达式（例如带
 *     {@code -SNAPSHOT}）会让 PF4J 在 {@code isPluginValid} 里抛运行时异常，而加载循环只捕获
 *     {@code PluginRuntimeException}，结果是一个坏表达式拖垮整批加载。这里先把非法值归一到 {@code *}，
 *     并把原因记入描述符，交给描述符体检阶段统一报错。</li>
 * </ol>
 *
 * @author zcd
 */
public final class JellyfishPluginDescriptorFinder extends PropertiesPluginDescriptorFinder {

    /** 版本约束预检器：无状态，整个解析器共用一个实例。 */
    private static final DefensiveVersionManager VERSION_MANAGER = new DefensiveVersionManager();

    @Override
    protected DefaultPluginDescriptor createPluginDescriptorInstance() {
        return new JellyfishPluginDescriptor();
    }

    @Override
    protected PluginDescriptor createPluginDescriptor(Properties properties) {
        JellyfishPluginDescriptor descriptor = (JellyfishPluginDescriptor) super.createPluginDescriptor(properties);
        if (StringUtils.isBlank(properties.getProperty(PluginProperties.PLUGIN_CLASS))) {
            descriptor.addLoadError("缺少必需的 " + PluginProperties.PLUGIN_CLASS);
        }
        descriptor.addTags(properties.getProperty(PluginProperties.JELLYFISH_TAGS));
        rejectNamespaceSeparator(descriptor);
        normalizeVersion(descriptor, properties.getProperty(PluginProperties.PLUGIN_VERSION));
        normalizeRequires(descriptor, properties.getProperty(PluginProperties.PLUGIN_REQUIRES));
        return descriptor;
    }

    @Override
    public PluginDescriptor find(Path pluginPath) {
        PluginDescriptor descriptor = super.find(pluginPath);
        // classpath 护栏需要看插件内容，只有拿到路径才能做，因此挂在 find 而不是 createPluginDescriptor
        PluginClasspathGuard.check(pluginPath, (JellyfishPluginDescriptor) descriptor);
        return descriptor;
    }

    /**
     * 拒绝带 owner 命名空间分隔符的插件标识。
     * <p>
     * {@code ::} 被保留给「插件内子单元」的 owner（形如 {@code pluginId::子标识}），
     * 而回收是按「命名空间 + 分隔符」前缀做的。若允许插件自带这个分隔符，
     * 一个叫 {@code x::y} 的插件就会把自己的注册挂在命名空间 {@code x} 下，
     * 于是 {@code x} 停止时会把它的注册一并抺掉——一个插件停掉另一个插件的工具，
     * 而且现场看上去一切正常。在加载期直接拦下，比事后追这样一个越界回收便宜得多。
     *
     * @param descriptor 描述符
     */
    private static void rejectNamespaceSeparator(JellyfishPluginDescriptor descriptor) {
        String pluginId = descriptor.getPluginId();
        if (pluginId == null || !pluginId.contains(PluginOwnerNamespace.SEPARATOR)) {
            return;
        }
        descriptor.addLoadError(PluginProperties.PLUGIN_ID + " 不得包含 \""
                + PluginOwnerNamespace.SEPARATOR
                + "\"：该分隔符保留给插件内部子单元的 owner 命名空间（{pluginId}"
                + PluginOwnerNamespace.SEPARATOR + "{子标识}），"
                + "自带会让回收范围误伤其它插件的注册");
    }

    /**
     * 补齐缺失的插件版本，避免下游依赖解析拿到 {@code null}。
     *
     * @param descriptor 描述符
     * @param version    原始版本号，可为 {@code null} 或空白
     */
    private static void normalizeVersion(JellyfishPluginDescriptor descriptor, String version) {
        if (StringUtils.isBlank(version)) {
            descriptor.overrideVersion(JellyfishPluginDescriptor.FALLBACK_VERSION);
        }
    }

    /**
     * 预检内核兼容版本约束，非法时归一为通配并记录原因。
     *
     * @param descriptor 描述符
     * @param requires   原始约束表达式，可为 {@code null} 或空白
     */
    private static void normalizeRequires(JellyfishPluginDescriptor descriptor, String requires) {
        if (StringUtils.isBlank(requires) || VERSION_MANAGER.isValidConstraint(requires)) {
            return;
        }
        descriptor.addLoadError(PluginProperties.PLUGIN_REQUIRES + " 不是合法的 semver 约束: " + requires);
        descriptor.overrideRequires("*");
    }
}
