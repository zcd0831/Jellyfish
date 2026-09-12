package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.EventRegistrar;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandHandler;
import zcd.jellyfish.api.event.command.CommandRegistrar;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.command.PluginCommandHandler;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.infra.event.command.CommandRegistry;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 插件能力上下文实现：同时实现两个 Registrar，把 {@code pluginId} 绑定为 owner。
 * <p>
 * {@code owner} 贯穿注册、覆盖记录、卸载回收、诊断快照四件事，是插件系统能管住资源的那根线；
 * 插件侧只看到一个 {@link PluginContext}，不感知 Guava，也不需要自行反注册。
 *
 * @author zcd
 */
public final class PluginContextImpl implements PluginContext, CommandRegistrar, EventRegistrar {

    /** 插件标识，作为所有注册项的来源。 */
    private final String pluginId;

    /** 细粒度命令注册表。 */
    private final CommandRegistry commandRegistry;

    /** 细粒度通知注册表。 */
    private final EventRegistry eventRegistry;

    /** 通知发布入口。 */
    private final EventPublisher publisher;

    /**
     * 构造插件上下文。
     *
     * @param pluginId        插件标识
     * @param commandRegistry 命令注册表
     * @param eventRegistry   通知注册表
     * @param publisher       通知发布入口
     */
    PluginContextImpl(String pluginId, CommandRegistry commandRegistry,
                      EventRegistry eventRegistry, EventPublisher publisher) {
        this.pluginId = pluginId;
        this.commandRegistry = commandRegistry;
        this.eventRegistry = eventRegistry;
        this.publisher = publisher;
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
    public Subscription register(String name, PluginCommandHandler handler, RegisterOptions options) {
        CommandHandler<PluginCommand, Object> adapter = handler::handle;
        return commandRegistry.register(pluginId, true, PluginCommand.class, name, adapter, options);
    }

    @Override
    public <C extends Command<R>, R> Subscription register(Class<C> commandType, String routeKey,
                                                           CommandHandler<C, R> handler, RegisterOptions options) {
        return commandRegistry.register(pluginId, true, commandType, routeKey, handler, options);
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
