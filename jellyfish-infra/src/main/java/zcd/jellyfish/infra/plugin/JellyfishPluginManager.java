package zcd.jellyfish.infra.plugin;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginFactory;
import org.pf4j.PluginState;
import org.pf4j.PluginStateEvent;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;
import org.pf4j.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.JellyfishEventBus;

/**
 * PF4J 插件管理器的子类：挂载本项目的描述符解析器、插件工厂、状态提供者与版本管理器，
 * 并把 PF4J 的单插件生命周期入口包成「异常不外溢」的安全方法。
 * <p>
 * <b>必须自己兜底的原因（实测 PF4J 3.14.1）</b>：
 * <ul>
 *     <li>{@code startPlugin(id)} 没有异常处理，插件抛错会直接冒泡——而热部署走的正是这条；</li>
 *     <li>{@code stopPlugin(id)} 只捕获 {@code PluginRuntimeException}，而 {@code JellyfishException}
 *     继承 {@code RuntimeException}，不在其中；</li>
 *     <li>只有批量 {@code startPlugins()} 自带 per-plugin 的 catch。</li>
 * </ul>
 * 若不兜底，「插件故障不影响内核与其它插件」这条硬约束就会在热部署与关闭路径上失效。
 * <p>
 * <b>构造期陷阱</b>：父类构造器内部就会调用 {@code createPluginDescriptorFinder()} /
 * {@code createPluginFactory()} / {@code createPluginStatusProvider()} / {@code createVersionManager()}，
 * 那时子类字段尚未赋值。因此这些覆写方法只能返回<b>延迟读取</b>成员字段的协作者，
 * 不能直接读字段。
 *
 * @author zcd
 */
final class JellyfishPluginManager extends DefaultPluginManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(JellyfishPluginManager.class);

    /** 交互枢纽：创建能力上下文，并按 owner 回收注册。 */
    private final JellyfishEventBus eventBus;

    /** 插件运行时装配输入。 */
    private final PluginRuntimeConfig runtimeConfig;

    /**
     * 配置驱动的状态提供者。
     * <p>
     * <b>刻意不写初始化器</b>：本字段由 {@link #createPluginStatusProvider()} 在父类构造器内赋值，
     * 而字段初始化器要等父类构造器返回后才执行——若在此处再 {@code new} 一个，会把已注册到
     * 父类的实例覆盖掉，导致后续启用 / 禁用开关作用在另一个对象上。
     */
    private ConfigPluginStatusProvider statusProvider;

    /**
     * 构造管理器。
     *
     * @param eventBus      交互枢纽，不可为 {@code null}
     * @param runtimeConfig 装配输入，不可为 {@code null}
     */
    JellyfishPluginManager(JellyfishEventBus eventBus, PluginRuntimeConfig runtimeConfig) {
        super(runtimeConfig.getPluginsRoots());
        // 赋值放在 super 之后：此前 create*() 已被调用，但它们只在后续 find/create/isPluginDisabled 时才读字段
        this.eventBus = eventBus;
        this.runtimeConfig = runtimeConfig;
        this.statusProvider.attach(runtimeConfig);
    }

    @Override
    protected PluginDescriptorFinder createPluginDescriptorFinder() {
        return new JellyfishPluginDescriptorFinder();
    }

    @Override
    protected PluginFactory createPluginFactory() {
        // 工厂持有 this，只在 create() 被调用时才读 eventBus
        return new JellyfishPluginFactory(this);
    }

    @Override
    protected PluginStatusProvider createPluginStatusProvider() {
        // 此处不能读 runtimeConfig（尚未赋值），只创建空实例，配置由构造器体注入
        this.statusProvider = new ConfigPluginStatusProvider();
        return this.statusProvider;
    }

    @Override
    protected VersionManager createVersionManager() {
        return new DefensiveVersionManager();
    }

    /**
     * 由描述符构造插件声明，并附上该插件的配置段。
     *
     * @param descriptor 插件描述符，不可为 {@code null}
     * @return 插件声明
     */
    PluginDeclaration declarationOf(JellyfishPluginDescriptor descriptor) {
        return descriptor.toDeclaration(runtimeConfig.configurationOf(descriptor.getPluginId()));
    }

    /**
     * 为插件声明创建能力上下文。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @return 能力上下文
     */
    PluginContext contextOf(PluginDeclaration declaration) {
        return eventBus.pluginContext(declaration);
    }

    /**
     * 安全加载：包住 PF4J 的加载循环，避免非 {@code PluginRuntimeException} 外溢。
     * <p>
     * 语义必须写清：本方法只保证「进程不因加载崩」，<b>不保证「其余插件已加载」</b>——
     * PF4J 的循环可能在中间就断了。真正的逐插件隔离靠
     * {@link DefensiveVersionManager} 与「描述符解析不抛业务异常」。
     */
    void safeLoadPlugins() {
        try {
            loadPlugins();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("插件加载中断，可能有插件未被加载", e);
        }
    }

    /**
     * 安全启动：把启动失败转成 {@code FAILED} 状态，并回滚本次已经完成的注册。
     * <p>
     * 回滚顺序是先 {@code stopPlugin}（未进入 STARTED 时 PF4J 会直接跳过，属安全空操作）
     * 再回收注册：不回滚就会留下「插件已失败、工具还能调」的幽灵注册。
     *
     * @param pluginId 插件标识
     * @return 启动后的状态；失败时为 {@code FAILED}
     */
    PluginState safeStart(String pluginId) {
        try {
            return startPlugin(pluginId);
        } catch (RuntimeException | LinkageError e) {
            // 刻意宽catch：插件是降级单元，其启动失败不能外溢给调用方（热部署路径没有异常保护）
            LOG.error("插件启动失败: {}", pluginId, e);
            rollback(pluginId, e);
            return PluginState.FAILED;
        }
    }

    /**
     * 回滚一次失败的启动：尽力停止，然后按 owner 回收注册，最后标记失败。
     * <p>
     * 标记失败放在最后，否则 {@code stopPlugin} 会把状态改写回 {@code STOPPED}，
     * 使这次失败在插件列表里看不出来。
     *
     * @param pluginId 插件标识
     * @param cause    启动失败原因
     */
    private void rollback(String pluginId, Throwable cause) {
        try {
            stopPlugin(pluginId);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("插件启动失败后的回滚停止也失败: {}", pluginId, e);
        } finally {
            eventBus.unregisterAll(pluginId);
        }
        markFailed(pluginId, cause);
    }

    /**
     * 安全停止：先停止插件，再按 owner 回收注册。
     * <p>
     * 回收放在 {@code finally} 里是刻意的：停止失败同样要回收，否则会留下
     * 「插件已停止、工具还能调」的幽灵注册——这比停止失败本身更难排查。
     *
     * @param pluginId 插件标识
     * @return 停止后的状态；失败时为 {@code FAILED}
     */
    PluginState safeStop(String pluginId) {
        try {
            return stopPlugin(pluginId);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("插件停止失败: {}", pluginId, e);
            markFailed(pluginId, e);
            return PluginState.FAILED;
        } finally {
            eventBus.unregisterAll(pluginId);
        }
    }

    /**
     * 安全卸载：卸载插件并回收注册。
     *
     * @param pluginId 插件标识
     * @return 卸载成功返回 {@code true}
     */
    boolean safeUnload(String pluginId) {
        try {
            return unloadPlugin(pluginId);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("插件卸载失败: {}", pluginId, e);
            markFailed(pluginId, e);
            return false;
        } finally {
            eventBus.unregisterAll(pluginId);
        }
    }

    /**
     * 把插件标记为失败并补齐状态通知。
     * <p>
     * {@code setPluginState} 与 {@code setFailedException} 是 {@code public}，
     * {@code firePluginStateEvent} 是 {@code protected}（本类是子类），三者凑齐才能自己补失败态。
     *
     * @param pluginId 插件标识
     * @param cause    失败原因
     */
    void markFailed(String pluginId, Throwable cause) {
        PluginWrapper wrapper = getPlugin(pluginId);
        if (wrapper == null) {
            return;
        }
        wrapper.setFailedException(cause);
        wrapper.setPluginState(PluginState.FAILED);
        firePluginStateEvent(new PluginStateEvent(this, wrapper, PluginState.FAILED));
    }
}
