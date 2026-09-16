package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
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
 * 「注册边界」在这里不靠清单，而靠本类暴露的入口本身：插件只能拿到 {@link PluginContext} 的几个方法，
 * 能注册什么完全取决于它拿得到哪些请求类型。核心内部使用的请求类型不外露，插件也就无从注册。
 * <p>
 * <b>为何要提供子上下文</b>：插件常由多个彼此独立的子单元组成（脚本插件的每个脚本、多后端插件的每个
 * 后端……），若它们的注册全挂在同一个插件标识下，诊断输出就分不出「这个工具是谁提供的」，
 * 也无法按子单元治理。{@link #subContext(String)} 把子单元放到自己的命名空间下，
 * 而 {@code PluginContextFactory.release} 按命名空间回收——「可归因」与「一次收干净」因此同时成立。
 * <p>
 * <b>子身份恒从当前身份派生</b>，插件无法构造出一个与自己无关的 owner，因此这条能力不会变成越权入口。
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
    public PluginContext subContext(String childId) {
        // 身份从「当前」身份派生而非从根插件标识派生：子上下文再派生子上下文就会自然形成
        // a::b::c 这样的层级，而回收侧的前缀匹配本就支持任意深度，无需特殊处理
        String childPluginId = pluginId() + PluginOwnerNamespace.SEPARATOR
                + PluginOwnerNamespace.requireChildId(childId);
        return new PluginContextImpl(PluginDeclaration.of(childPluginId, declaration.getConfiguration()),
                extensions, events);
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
