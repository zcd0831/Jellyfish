package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.EventRegistrar;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.CommandRegistrar;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.PluginRequestHandler;
import zcd.jellyfish.api.event.callback.ToolCallRequest;
import zcd.jellyfish.api.event.callback.ToolCallResult;
import zcd.jellyfish.api.event.callback.ToolRegistrar;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件能力上下文实现：按扩展点实现各 Registrar，把 {@code pluginId} 绑定为 owner。
 * <p>
 * {@code owner} 贯穿注册、覆盖记录、卸载回收、诊断快照四件事，是插件系统能管住资源的那根线；
 * 插件侧只看到一个 {@link PluginContext}，不感知 Guava，也不需要自行反注册。
 * <p>
 * 插件只能经本类暴露的扩展点入口注册处理器（{@code tool.provide} / {@code command.provide}），
 * 无法触达通用回调注册，因此「注册核心独占回调（如权限检查）」在编译期就不可能。
 *
 * @author zcd
 */
public final class PluginContextImpl implements PluginContext, ToolRegistrar, CommandRegistrar, EventRegistrar {

    /** 插件标识，作为所有注册项的来源。 */
    private final String pluginId;

    /** 细粒度回调注册表。 */
    private final CallbackRegistry callbackRegistry;

    /** 细粒度通知注册表。 */
    private final EventRegistry eventRegistry;

    /** 通知发布入口。 */
    private final EventPublisher publisher;

    /**
     * 构造插件上下文。
     *
     * @param pluginId        插件标识
     * @param callbackRegistry 回调注册表
     * @param eventRegistry   通知注册表
     * @param publisher       通知发布入口
     */
    PluginContextImpl(String pluginId, CallbackRegistry callbackRegistry,
                      EventRegistry eventRegistry, EventPublisher publisher) {
        this.pluginId = pluginId;
        this.callbackRegistry = callbackRegistry;
        this.eventRegistry = eventRegistry;
        this.publisher = publisher;
    }

    @Override
    public ToolRegistrar tools() {
        return this;
    }

    @Override
    public CommandRegistrar commands() {
        return this;
    }

    @Override
    public EventRegistrar events() {
        return this;
    }

    @Override
    public EventPublisher publisher() {
        return publisher;
    }

    @Override
    public Subscription register(String toolName, CallbackHandler<ToolCallRequest, ToolCallResult> handler,
                                RegisterOptions options) {
        return callbackRegistry.register(pluginId, true, ToolCallRequest.class, toolName, handler, options);
    }

    @Override
    public Subscription register(String name, PluginRequestHandler handler, RegisterOptions options) {
        CallbackHandler<PluginRequest, Object> adapter = handler::handle;
        return callbackRegistry.register(pluginId, true, PluginRequest.class, name, adapter, options);
    }

    @Override
    public <E extends JellyfishEvent> Subscription subscribe(Class<E> eventType, Predicate<E> filter,
                                                             Consumer<E> listener) {
        return eventRegistry.subscribe(pluginId, eventType, filter, listener);
    }

    /**
     * 获取插件标识。
     *
     * @return 插件标识
     */
    public String getPluginId() {
        return pluginId;
    }
}
