package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件上下文实现：把 {@code pluginId} 绑定为全部注册的 owner。
 * <p>
 * {@code owner} 贯穿注册、覆盖记录、卸载回收、诊断快照四件事，是插件系统能管住资源的那根线；
 * 插件侧只看到一个 {@link PluginContext}，不感知 Guava，也不需要自行反注册。
 * <p>
 * 「注册边界」在这里不靠清单，而靠本类暴露的入口本身：插件只能拿到 {@link PluginContext} 的四个方法，
 * 能注册什么完全取决于它拿得到哪些请求类型。核心内部使用的请求类型不外露，插件也就无从注册。
 * <p>
 * <b>为什么在 {@code infra/plugin} 而不是 {@code infra/event}</b>：本类是插件侧的能力上下文，
 * 与插件加载同属插件运行时；它只用 {@code event} 包两个注册表的公开方法，不依赖任何包级私有细节，
 * 因此可以外置。代价是 {@code infra.event} 的 {@link zcd.jellyfish.infra.event.JellyfishEventBus}
 * 需要反向引用本类来装配上下文，形成包级循环（{@code infra.event} ⇄ {@code infra.plugin}）；
 * 模块级依赖仍单向，这条循环只服务于「插件上下文装配」这一件事，不得扩散到其它协作。
 *
 * @author zcd
 */
public final class PluginContextImpl implements PluginContext {

    /** 插件声明，提供身份与配置段。 */
    private final PluginDeclaration declaration;

    /** 细粒度回调注册表。 */
    private final CallbackRegistry callbackRegistry;

    /** 细粒度通知注册表。 */
    private final EventRegistry eventRegistry;

    /** 通知发布入口。 */
    private final EventPublisher publisher;

    /**
     * 构造插件上下文。
     *
     * @param declaration      插件声明，不可为 {@code null}
     * @param callbackRegistry 回调注册表，不可为 {@code null}
     * @param eventRegistry    通知注册表，不可为 {@code null}
     * @param publisher        通知发布入口，不可为 {@code null}
     */
    public PluginContextImpl(PluginDeclaration declaration, CallbackRegistry callbackRegistry,
                             EventRegistry eventRegistry, EventPublisher publisher) {
        this.declaration = declaration;
        this.callbackRegistry = callbackRegistry;
        this.eventRegistry = eventRegistry;
        this.publisher = publisher;
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
        return callbackRegistry.register(pluginId(), requestType, routeKey, descriptor, handler, options);
    }

    @Override
    public <C extends ExtensionRequest<R>, R> Subscription contribute(Class<C> requestType, Object descriptor,
                                                                     ExtensionHandler<C, R> handler,
                                                                     RegisterOptions options) {
        return callbackRegistry.contribute(pluginId(), requestType, descriptor, handler, options);
    }

    @Override
    public <E extends JellyfishEvent> Subscription observe(Class<E> eventType, Predicate<E> filter,
                                                           Consumer<E> listener) {
        return eventRegistry.subscribe(pluginId(), eventType, filter, listener);
    }

    @Override
    public void emit(JellyfishEvent event) {
        publisher.publish(event);
    }
}
