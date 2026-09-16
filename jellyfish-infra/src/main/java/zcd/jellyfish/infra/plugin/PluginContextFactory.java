package zcd.jellyfish.infra.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
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
 * 因此 {@link #release(String)} 只需调用一次表的按命名空间回收，不存在「一半还在表里」的幽灵注册。
 * <p>
 * <b>owner 命名空间</b>：一个插件可以给同一 {@code pluginId} 下的多个子单元各分一个 owner
 * （形如 {@code pluginId::子标识}），以获得可归因的诊断与更细的粒度。框架回收只拿得到
 * {@code pluginId}，因此 {@code release} 按命名空间回收：{@code pluginId} 自身与
 * {@code pluginId::*} 一起清干净，否则子来源的注册会在插件停止后残留成
 * 「插件已停、工具还能调」的幽灵注册。分隔符由 {@link PluginOwnerNamespace} 定义——
 * 它同时被插件侧使用，因此必须只有一个真源。
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
     * 按 owner 命名空间回收该插件的全部注册：{@code pluginId} 自身与 {@code pluginId::*} 一并清掉。
     * <p>
     * 插件卸载、停止与启动失败回滚都走这里；重复调用是安全的空操作。
     * <p>
     * <b>为什么不是精确匹配</b>：插件可以给子单元分独立的 owner（如脚本桥接插件的每个脚本），
     * 而框架只拿得到 {@code pluginId}。精确匹配会留下子来源的幽灵注册，
     * 而这种残留比「停止失败」本身更难排查——它会表现为「插件已停、工具还能调」。
     *
     * @param pluginId 插件标识（同时也是 owner 命名空间的根），不可为空白
     * @return 回收的注册数量
     */
    public int release(String pluginId) {
        int removed = registry.removeAllUnder(pluginId, PluginOwnerNamespace.SEPARATOR);
        LOG.info("已回收插件注册: pluginId={} registrations={}", pluginId, removed);
        return removed;
    }
}
