package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.notification.EventDispatchResult;
import zcd.jellyfish.infra.event.notification.EventRegistry;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextImpl} 的单元测试：验证四个注册/发布入口都绑定 {@code pluginId}。
 * <p>
 * 这里使用真实注册表而非 mock：本类的职责只是把 {@code pluginId} 作为 owner 透传下去，
 * 用真实表可以顺带验证注册真的落到了同一份 {@link TypeRegistry} 上。
 *
 * @author zcd
 */
class PluginContextImplTest {

    /** 共用注册表。 */
    private final TypeRegistry typeRegistry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(typeRegistry);

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    /** 记录发布的通知，用于验证发布入口透传。 */
    private final List<JellyfishEvent> published = new ArrayList<>();

    /** 通知发布入口。 */
    private final EventPublisher publisher = published::add;

    /** 被测插件上下文。 */
    private final PluginContextImpl context = new PluginContextImpl(
            PluginDeclaration.of("plugin-a"), extensions, eventRegistry, publisher);

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
                PluginDeclaration.of("plugin-b", configuration), extensions, eventRegistry, publisher);

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
    void contribute_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = request -> "42";

        // When
        context.contribute(CommandRequest.class, handler, RegisterOptions.order(3));

        // Then
        assertSame(handler, extensions.handlers(CommandRequest.class, null).get(0));
        assertTrue(typeRegistry.snapshot().render().contains("plugin-a"));
    }

    @Test
    void observe_should_apply_filter_and_bind_plugin_owner() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        context.observe(ConfigWarningEvent.class, event -> "keep".equals(event.getSource()), received::add);

        // When
        EventDispatchResult rejected = eventRegistry.dispatch(new ConfigWarningEvent("drop", "message"));
        EventDispatchResult accepted = eventRegistry.dispatch(new ConfigWarningEvent("keep", "message"));

        // Then
        assertEquals(0, rejected.getMatched());
        assertEquals(1, accepted.getMatched());
        assertEquals(1, received.size());
        assertTrue(eventRegistry.render().contains("plugin-a"));
    }

    @Test
    void observe_should_listen_to_every_event_when_filter_omitted() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        context.observe(ConfigWarningEvent.class, received::add);

        // When
        eventRegistry.dispatch(new ConfigWarningEvent("any", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void emit_should_delegate_to_publisher() {
        // When
        context.emit(new ConfigWarningEvent("source", "message"));

        // Then
        assertEquals(1, published.size());
    }
}
