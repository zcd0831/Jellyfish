package zcd.jellyfish.infra.plugin;

import org.pf4j.Plugin;
import org.pf4j.PluginFactory;
import org.pf4j.PluginWrapper;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * 插件工厂：用插件的类加载器实例化 {@link JellyfishPlugin}，并包装成 PF4J 认识的 {@link Plugin}。
 * <p>
 * 这是「插件作者完全不需要知道 PF4J 存在」的落点：描述符里的 {@code plugin.class} 指向的是
 * {@code JellyfishPlugin} 实现类，而不是 {@code org.pf4j.Plugin} 子类，因此 PF4J 默认的
 * {@code DefaultPluginFactory}（会强转 {@code org.pf4j.Plugin}）被本类替换。
 * <p>
 * 实例化要求公开无参构造器：插件由插件的类加载器加载，无法走 Dagger 装配。
 *
 * @author zcd
 */
final class JellyfishPluginFactory implements PluginFactory {

    /** 所属管理器，用于取配置段与创建能力上下文。 */
    private final JellyfishPluginManager manager;

    /**
     * 构造插件工厂。
     *
     * @param manager 所属管理器，不可为 {@code null}
     */
    JellyfishPluginFactory(JellyfishPluginManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    @Override
    public Plugin create(PluginWrapper wrapper) {
        JellyfishPluginDescriptor descriptor = JellyfishPluginDescriptor.of(wrapper);
        JellyfishPlugin plugin = instantiate(descriptor, wrapper.getPluginClassLoader());
        PluginDeclaration declaration = manager.declarationOf(descriptor);
        PluginContext context = manager.contextOf(declaration);
        return new JellyfishPluginAdapter(wrapper, plugin, context);
    }

    /**
     * 用插件类加载器加载并实例化插件实现。
     *
     * @param descriptor 插件描述符
     * @param classLoader 插件类加载器
     * @return 插件实例
     * @throws JellyfishException 类加载失败、类型不符或缺少公开无参构造器时抛出
     */
    private static JellyfishPlugin instantiate(JellyfishPluginDescriptor descriptor, ClassLoader classLoader) {
        String className = descriptor.getPluginClass();
        Class<?> type;
        try {
            type = classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new JellyfishException("插件入口类找不到: " + className, e);
        }
        if (!JellyfishPlugin.class.isAssignableFrom(type)) {
            throw new JellyfishException("plugin.class 必须实现 JellyfishPlugin: " + className);
        }
        if (Modifier.isAbstract(type.getModifiers()) || type.isInterface()) {
            throw new JellyfishException("plugin.class 不能是抽象类或接口: " + className);
        }
        try {
            return (JellyfishPlugin) type.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new JellyfishException("plugin.class 必须提供公开无参构造器: " + className, e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                 | ExceptionInInitializerError e) {
            throw new JellyfishException("插件实例化失败: " + className, e);
        }
    }
}
