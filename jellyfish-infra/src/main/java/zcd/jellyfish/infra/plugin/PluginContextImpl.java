package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件上下文实现：把 {@code pluginId} 绑定为全部注册的 owner。
 * <p>
 * {@code owner} 贯穿注册、覆盖记录、卸载回收、诊断快照四件事，是插件系统能管住资源的那根线；
 * 插件侧只看到一个 {@link PluginContext}，不感知注册表与派发策略，也不需要自行反注册。
 * <p>
 * 「注册边界」在这里不靠清单，而靠本类暴露的入口本身：插件只能拿到 {@link PluginContext} 的四个方法，
 * 能注册什么完全取决于它拿得到哪些请求类型。核心内部使用的请求类型不外露，插件也就无从注册。
 * <p>
 * <b>为什么在 {@code infra/plugin} 而不是策略包里</b>：本类是插件侧的能力上下文，与插件加载同属插件运行时；
 * 它只调用 {@link ExtensionRegistry} 与 {@link EventChannel} 的公开方法，不依赖任何包级私有细节，
 * 因此装配入口（{@link PluginContextFactory}）可以外置而不产生包级循环。
 *
 * @author zcd
 */
public final class PluginContextImpl implements PluginContext {

    /** 插件声明，提供身份与配置段。 */
    private final PluginDeclaration declaration;

    /** 同步扩展点策略（同一份 TypeRegistry 的同步视图）。 */
    private final ExtensionRegistry extensions;

    /** 事件通道：插件的订阅与发布都落在这里。 */
    private final EventChannel events;

    /**
     * 构造插件上下文。
     *
     * @param declaration 插件声明，不可为 {@code null}
     * @param extensions  同步扩展点策略，不可为 {@code null}
     * @param events      事件通道，不可为 {@code null}
     */
    public PluginContextImpl(PluginDeclaration declaration, ExtensionRegistry extensions, EventChannel events) {
        this.declaration = declaration;
        this.extensions = extensions;
        this.events = events;
    }

    @Override
    public String pluginId() {
        return declaration.getPluginId();
    }

    @Override
    public Map<String, Object> configuration() {
        return declaration.getConfiguration();
    }

    @Override
    public <C extends ExtensionRequest<R>, R> Subscription handle(Class<C> requestType, String routeKey,
                                                                 Object descriptor, ExtensionHandler<C, R> handler,
                                                                 RegisterOptions options) {
        return extensions.handle(pluginId(), requestType, routeKey, descriptor, handler, options);
    }

    @Override
    public <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType, Object descriptor,
                                                                     ExtensionHandler<C, R> handler,
                                                                     RegisterOptions options) {
        return extensions.contribute(pluginId(), requestType, descriptor, handler, options);
    }

    @Override
    public <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Predicate<E> filter,
                                                           Consumer<E> listener) {
        return events.subscribe(pluginId(), eventType, filter, listener);
    }

    @Override
    public void emit(JellyfishEvent event) {
        events.publish(event);
    }
}
