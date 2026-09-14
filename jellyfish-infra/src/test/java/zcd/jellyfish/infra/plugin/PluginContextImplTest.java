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
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.notification.EventDispatchResult;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextImpl} 的单元测试：验证三个注册入口与发布入口都绑定 {@code pluginId}。
 * <p>
 * 这里使用真实注册表而非 mock，因为 {@link CallbackRegistry} 与 {@link EventRegistry} 均为 final，
 * 且本类的职责只是把 {@code pluginId} 作为 owner 透传下去。
 *
 * @author zcd
 */
class PluginContextImplTest {

    /** 回调注册表。 */
    private final CallbackRegistry callbackRegistry = new CallbackRegistry();

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    /** 记录发布的通知，用于验证发布入口透传。 */
    private final List<JellyfishEvent> published = new ArrayList<>();

    /** 通知发布入口。 */
    private final EventPublisher publisher = published::add;

    /** 被测插件上下文。 */
    private final PluginContextImpl context = new PluginContextImpl(
            PluginDeclaration.of("plugin-a"), callbackRegistry, eventRegistry, publisher);

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
                PluginDeclaration.of("plugin-b", configuration), callbackRegistry, eventRegistry, publisher);

        // Then
        assertEquals(4, configured.configuration().get("precision"));
    }

    @Test
    void handle_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler = callback -> new ToolCallResult("calculator", 42);

        // When
        context.handle(ToolCallRequest.class, "calculator", handler);

        // Then
        ToolCallRequest callback = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap());
        assertSame(handler, callbackRegistry.resolve(callback).get(0));
        assertTrue(callbackRegistry.render().contains("plugin-a"));
    }

    @Test
    void contribute_should_register_under_plugin_owner() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = callback -> "42";

        // When
        context.contribute(CommandRequest.class, handler, RegisterOptions.order(3));

        // Then
        CommandRequest callback = new CommandRequest("calc", Object.class, null);
        assertSame(handler, callbackRegistry.resolve(callback).get(0));
        assertTrue(callbackRegistry.render().contains("plugin-a"));
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
