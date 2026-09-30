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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
 * <b>回收同时使上下文失效</b>：注册窗口的语义是「插件存活期」而不是「{@code start()} 之内」
 * （见 {@link PluginContextImpl}），窗口放宽之后，停止期与注册期真正重叠了起来。
 * 因此本工厂按根 {@code pluginId} 记录插件上下文的存活标记，{@code release} 时先关闭标记
 * 再回收注册——否则「先注册、再回收」这个顺序会留下一个谁也回收不到的幽灵注册。
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

    /** 运行时信息持有者：外壳启动期写入，插件上下文只读快照。 */
    private final RuntimeInfoHolder runtimeInfo;

    /** 根 {@code pluginId} → 存活标记；停止时据此让该插件的全部上下文失效。 */
    private final Map<String, ContextLifecycle> lifecycles = new ConcurrentHashMap<String, ContextLifecycle>();

    /**
     * 构造工厂。
     *
     * @param extensions  同步扩展点策略，不可为 {@code null}
     * @param events      事件通道，不可为 {@code null}
     * @param registry    共用注册表，不可为 {@code null}
     * @param runtimeInfo 运行时信息持有者，不可为 {@code null}
     */
    @Inject
    public PluginContextFactory(ExtensionRegistry extensions, EventChannel events, TypeRegistry registry,
                               RuntimeInfoHolder runtimeInfo) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.runtimeInfo = Objects.requireNonNull(runtimeInfo, "runtimeInfo must not be null");
    }

    /**
     * 为插件声明创建能力上下文，并登记它的存活标记以便停止时失效。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @return 能力上下文
     */
    public PluginContext create(PluginDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        ContextLifecycle lifecycle = new ContextLifecycle();
        ContextLifecycle previous = lifecycles.put(declaration.getPluginId(), lifecycle);
        if (previous != null) {
            // 同一个 pluginId 未经 release 又被创建：只可能来自装配错误或并发启动。
            // 方向是 fail-closed——关掉旧标记，旧上下文之后的任何注册都会当场失败
            LOG.warn("插件上下文被重复创建，已关闭上一条生命周期: pluginId={}", declaration.getPluginId());
            previous.close();
        }
        return new PluginContextImpl(declaration, extensions, events, lifecycle, runtimeInfo);
    }

    /**
     * 按 owner 命名空间回收该插件的全部注册：{@code pluginId} 自身与 {@code pluginId::*} 一并清掉。
     * <p>
     * 插件卸载、停止与启动失败回滚都走这里；重复调用是安全的空操作。
     * <p>
     * <b>先失效上下文、再回收注册</b>：两者之间若有插件的注册线程正在跑，它拿到的是「已关闭」
     * 而不是「注册成功但随即被回收」——后者会留下一个谁也回收不到的幽灵注册。
     * <p>
     * <b>为什么不是精确匹配</b>：插件可以给子单元分独立的 owner（如脚本桥接插件的每个脚本），
     * 而框架只拿得到 {@code pluginId}。精确匹配会留下子来源的幽灵注册，
     * 而这种残留比「停止失败」本身更难排查——它会表现为「插件已停、工具还能调」。
     *
     * @param pluginId 插件标识（同时也是 owner 命名空间的根），不可为空白
     * @return 回收的注册数量
     */
    public int release(String pluginId) {
        if (pluginId != null) {
            ContextLifecycle lifecycle = lifecycles.remove(pluginId);
            if (lifecycle != null) {
                lifecycle.close();
            }
        }
        int removed = registry.removeAllUnder(pluginId, PluginOwnerNamespace.SEPARATOR);
        LOG.info("已回收插件注册: pluginId={} registrations={}", pluginId, removed);
        return removed;
    }
}
