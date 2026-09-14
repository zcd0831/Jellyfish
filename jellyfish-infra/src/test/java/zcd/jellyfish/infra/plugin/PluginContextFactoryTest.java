package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextFactory} 的单元测试：验证 owner 绑定与一次性回收。
 *
 * @author zcd
 */
class PluginContextFactoryTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(registry);

    /** 事件通道。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);

    /** 被测工厂。 */
    private final PluginContextFactory factory = new PluginContextFactory(extensions, events, registry);

    @Test
    void create_should_bind_plugin_id_as_owner() {
        // Given
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));

        // When
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));

        // Then
        assertTrue(registry.snapshot().render().contains("<- plugin-a"));
    }

    @Test
    void create_should_produce_isolated_contexts_per_declaration() {
        // Given
        PluginContext first = factory.create(PluginDeclaration.of("plugin-a"));
        PluginContext second = factory.create(PluginDeclaration.of("plugin-b"));

        // Then
        assertEquals("plugin-a", first.pluginId());
        assertEquals("plugin-b", second.pluginId());
        assertNotEquals(first.pluginId(), second.pluginId());
    }

    @Test
    void release_should_drop_extension_handlers_and_event_subscriptions_together() {
        // Given：同一个插件既注册处理器又订阅事件
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        context.observe(ConfigWarningEvent.class, event -> {
            // 仅用于产生一条订阅
        });

        // When：一份表意味着回收是一次操作
        int removed = factory.release("plugin-a");

        // Then
        assertEquals(2, removed);
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void release_should_keep_other_plugins_registrations() {
        // Given
        PluginContext kept = factory.create(PluginDeclaration.of("plugin-b"));
        kept.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        factory.create(PluginDeclaration.of("plugin-a"))
                .handle(CommandRequest.class, "other", request -> CommandResult.ok("ok"));

        // When
        factory.release("plugin-a");

        // Then
        assertEquals(1, extensions.handlers(CommandRequest.class, "calc").size());
        assertThrows(ExtensionException.class, () -> extensions.handler(CommandRequest.class, "other"));
    }

    @Test
    void release_should_be_safe_for_unknown_owner() {
        // When / Then
        assertEquals(0, factory.release("never-registered"));
    }

    @Test
    void create_should_reject_null_declaration() {
        // When / Then：上下文实现同样校验声明，这里确认异常类型保持统一
        assertThrows(NullPointerException.class, () -> factory.create(null));
    }

    @Test
    void subscription_from_context_should_release_single_registration() {
        // Given
        PluginContext context = factory.create(PluginDeclaration.of("plugin-a"));
        context.handle(CommandRequest.class, "calc", request -> CommandResult.ok("ok"));
        Subscription subscription = context.handle(ToolCallRequest.class, "echo", request -> null);

        // When
        subscription.close();

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "echo").isEmpty());
        assertEquals(1, extensions.handlers(CommandRequest.class, "calc").size());
    }

    @Test
    void factory_should_reject_null_dependencies() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new PluginContextFactory(null, events, registry));
        assertThrows(NullPointerException.class, () -> new PluginContextFactory(extensions, null, registry));
        assertThrows(NullPointerException.class, () -> new PluginContextFactory(extensions, events, null));
    }
}
