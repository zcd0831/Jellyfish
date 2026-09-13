package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.ToolCallRequest;
import zcd.jellyfish.api.event.callback.ToolCallResult;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.callback.ExtensionPointRegistry;
import zcd.jellyfish.infra.event.notification.EventDispatchResult;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginContextImpl} 的单元测试：验证按扩展点暴露的注册入口与能力入口都绑定 pluginId。
 * <p>
 * 这里使用真实注册表而非 mock，因为 {@link CallbackRegistry} 与 {@link EventRegistry} 均为 final，
 * 且本类的职责只是把 {@code pluginId} 作为 owner 透传下去。
 *
 * @author zcd
 */
class PluginContextImplTest {

    /** 回调注册表。 */
    private final CallbackRegistry callbackRegistry = new CallbackRegistry(ExtensionPointRegistry.withBuiltIns());

    /** 通知注册表。 */
    private final EventRegistry eventRegistry = new EventRegistry();

    /** 记录发布的通知，用于验证 publisher 透传。 */
    private final List<JellyfishEvent> published = new ArrayList<>();

    /** 通知发布入口。 */
    private final EventPublisher publisher = published::add;

    /** 被测插件上下文。 */
    private final PluginContextImpl context = new PluginContextImpl("plugin-a", callbackRegistry, eventRegistry,
            publisher);

    @Test
    void registrars_and_events_should_return_self_and_publisher_should_return_injected() {
        // Then
        assertSame(context, context.tools());
        assertSame(context, context.commands());
        assertSame(context, context.events());
        assertSame(publisher, context.publisher());
        assertEquals("plugin-a", context.getPluginId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_named_request_should_register_under_plugin_owner() throws Exception {
        // Given
        List<String> handled = new ArrayList<>();
        context.commands().register("calculator", callback -> {
            handled.add(callback.getName());
            return "42";
        }, RegisterOptions.DEFAULT);

        // When
        CallbackHandler<?, ?> handler = callbackRegistry.resolveUnique(new PluginRequest("calculator", Object.class, null));
        Object result = ((CallbackHandler<PluginRequest, Object>) handler)
                .handle(new PluginRequest("calculator", Object.class, null));

        // Then
        assertEquals("42", result);
        assertEquals(1, handled.size());
        assertTrue(callbackRegistry.render().contains("plugin-a"));
    }

    @Test
    void register_tool_should_register_under_plugin_owner() {
        // Given
        CallbackHandler<ToolCallRequest, ToolCallResult> handler = callback -> new ToolCallResult("calculator", 42);

        // When
        context.tools().register("calculator", handler);

        // Then
        CallbackHandler<?, ?> resolved = callbackRegistry.resolveUnique(new ToolCallRequest("calculator",
                Collections.<String, Object>emptyMap(), null, 0L));
        assertSame(handler, resolved);
    }

    @Test
    void subscribe_should_apply_filter_and_bind_plugin_owner() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        context.subscribe(ConfigWarningEvent.class, event -> "keep".equals(event.getSource()), received::add);

        // When
        EventDispatchResult rejected = eventRegistry.dispatch(new ConfigWarningEvent("drop", "message"));
        EventDispatchResult accepted = eventRegistry.dispatch(new ConfigWarningEvent("keep", "message"));

        // Then
        assertEquals(0, rejected.getMatched());
        assertEquals(1, accepted.getMatched());
        assertEquals(1, received.size());
        assertTrue(eventRegistry.render().contains("plugin-a"));
    }
}
