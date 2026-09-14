package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextImpl} 的单元测试：验证四个注册/发布入口都绑定 {@code pluginId}。
 * <p>
 * 这里使用真实的注册表与事件通道（同步执行器）而非 mock：本类的职责只是把 {@code pluginId} 作为 owner
 * 透传下去，用真实组件可以顺带验证注册与订阅确实落到了同一份 {@link TypeRegistry} 上。
 *
 * @author zcd
 */
class PluginContextImplTest {

    /** 共用注册表。 */
    private final TypeRegistry typeRegistry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(typeRegistry);

    /** 事件通道：用默认线程池，测试以闩锁等待异步投递。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), typeRegistry);

    /** 被测插件上下文。 */
    private final PluginContextImpl context = new PluginContextImpl(
            PluginDeclaration.of("plugin-a"), extensions, events);

    @Test
    void pluginId_should_come_from_declaration() {
        // Then
        assertEquals("plugin-a", context.pluginId());
    }

    @Test
    void configuration_should_return_empty_map_when_not_configured() {
        // Then
        assertTrue(context.configuration().isEmpty());
    }

    @Test
    void configuration_should_return_declared_section_when_configured() {
        // Given
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("precision", 4);
        PluginContextImpl configured = new PluginContextImpl(
                PluginDeclaration.of("plugin-b", configuration), extensions, events);

        // Then
        assertEquals(4, configured.configuration().get("precision"));
    }

    @Test
    void handle_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler =
                request -> new ToolCallResult("calculator", 42);

        // When
        context.handle(ToolCallRequest.class, "calculator", handler);

        // Then
        assertSame(handler, extensions.handlers(ToolCallRequest.class, "calculator").get(0));
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void handle_should_keep_descriptor_together_with_handler() {
        // Given
        ToolDescriptor descriptor = new ToolDescriptor("calculator", "算一下");

        // When
        context.handle(ToolCallRequest.class, "calculator", descriptor, request -> null);

        // Then
        assertEquals(1, extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void contribute_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<CommandRequest, CommandResult> handler = request -> CommandResult.ok("42");

        // When
        context.contribute(CommandRequest.class, handler, RegisterOptions.order(3));

        // Then
        assertSame(handler, extensions.handlers(CommandRequest.class, null).get(0));
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void observe_should_apply_filter_and_bind_plugin_owner() throws InterruptedException {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> "keep".equals(event.getSource()), event -> {
            received.add(event);
            latch.countDown();
        });

        // When
        events.publish(new ConfigWarningEvent("drop", "message"));
        events.publish(new ConfigWarningEvent("keep", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("keep", received.get(0).getSource());
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void observe_should_listen_to_every_event_when_filter_omitted() throws InterruptedException {
        // Given
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> latch.countDown());

        // When
        events.publish(new ConfigWarningEvent("any", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void emit_should_publish_to_event_channel() throws InterruptedException {
        // Given
        List<JellyfishEvent> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        events.start();
        context.observe(ConfigWarningEvent.class, event -> {
            received.add(event);
            latch.countDown();
        });

        // When
        context.emit(new ConfigWarningEvent("source", "message"));

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
    }
}
