package zcd.jellyfish.infra.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import javax.inject.Inject;
import java.util.Objects;

/**
 * 插件上下文工厂：把 {@code pluginId} 绑定为 owner，并作为插件侧唯一的回收入口。
 * <p>
 * <b>为什么需要它</b>：插件运行时（PF4J 加载、体检、热部署）与「注册表 / 事件通道」是两件事。
 * 把装配插件上下文与按 owner 回收的职责集中到这里，插件运行时就不再感知注册表与派发策略，
 * 也消除了历史上 {@code infra.event ⇄ infra.plugin} 的包级循环。
 * <p>
 * <b>回收是一次操作</b>：同步处理器与事件订阅落在同一份 {@link TypeRegistry} 上，
 * 因此 {@link #release(String)} 只需调用一次表的按 owner 回收，不存在「一半还在表里」的幽灵注册。
 *
 * @author zcd
 */
public final class PluginContextFactory {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginContextFactory.class);

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions;

    /** 事件通道。 */
    private final EventChannel events;

    /** 共用注册表，用于按 owner 一次性回收。 */
    private final TypeRegistry registry;

    /**
     * 构造工厂。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     * @param events     事件通道，不可为 {@code null}
     * @param registry   共用注册表，不可为 {@code null}
     */
    @Inject
    public PluginContextFactory(ExtensionRegistry extensions, EventChannel events, TypeRegistry registry) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 为插件声明创建能力上下文。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @return 能力上下文
     */
    public PluginContext create(PluginDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        return new PluginContextImpl(declaration, extensions, events);
    }

    /**
     * 按 owner 回收该插件的全部注册。
     * <p>
     * 插件卸载、停止与启动失败回滚都走这里；重复调用是安全的空操作。
     *
     * @param pluginId 插件标识，不可为空白
     * @return 回收的注册数量
     */
    public int release(String pluginId) {
        int removed = registry.removeAll(pluginId);
        LOG.info("已回收插件注册: pluginId={} registrations={}", pluginId, removed);
        return removed;
    }
}
