package zcd.jellyfish.infra.event.notification;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventRegistry} 的单元测试：验证广播、父类型匹配、过滤、异常隔离与回收。
 *
 * @author zcd
 */
class EventRegistryTest {

    /** 被测注册表。 */
    private final EventRegistry registry = new EventRegistry();

    @Test
    void dispatch_should_invoke_subscriber() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When
        EventDispatchResult result = registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, result.getMatched());
        assertEquals(0, result.getErrors());
        assertEquals(1, received.size());
    }

    @Test
    void dispatch_should_match_subscription_of_parent_type() {
        // Given
        List<JellyfishEvent> received = new ArrayList<>();
        registry.subscribe("metrics", JellyfishEvent.class, null, received::add);

        // When
        registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void dispatch_should_skip_when_filter_rejects() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ConfigWarningEvent.class, event -> false, received::add);

        // When
        EventDispatchResult result = registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(0, result.getMatched());
        assertTrue(received.isEmpty());
    }

    @Test
    void dispatch_should_isolate_listener_exception() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("broken", ConfigWarningEvent.class, null, event -> {
            throw new IllegalStateException("boom");
        });
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When
        EventDispatchResult result = registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(2, result.getMatched());
        assertEquals(1, result.getErrors());
        assertEquals(1, received.size());
    }

    @Test
    void dispatch_should_report_unmatched_when_no_subscriber() {
        // When
        EventDispatchResult result = registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(0, result.getMatched());
    }

    @Test
    void dispatch_should_skip_subscription_when_event_type_unrelated() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When：已有订阅类型与本次事件类型不可赋值
        EventDispatchResult result = registry.dispatch(new SessionCreatedEvent("agent-a", "session-1"));

        // Then
        assertEquals(0, result.getMatched());
        assertTrue(received.isEmpty());
    }

    @Test
    void unsubscribeAll_should_remove_subscriptions_of_owner() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("plugin-a", ConfigWarningEvent.class, null, received::add);
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When
        int removed = registry.unsubscribeAll("plugin-a");
        registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, removed);
        assertEquals(1, received.size());
    }

    @Test
    void subscription_close_should_remove_subscription() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        Subscription subscription = registry.subscribe("plugin-a", ConfigWarningEvent.class, null, received::add);

        // When
        subscription.close();
        registry.dispatch(new ConfigWarningEvent("path", "message"));

        // Then
        assertTrue(received.isEmpty());
        assertTrue(registry.isEmpty());
    }

    @Test
    void render_should_return_empty_when_no_subscription() {
        // Then
        assertTrue(registry.render().isEmpty());
    }

    @Test
    void clear_should_remove_all_subscriptions() {
        // Given
        registry.subscribe("metrics", ConfigWarningEvent.class, null, event -> {
        });

        // When
        registry.clear();

        // Then
        assertTrue(registry.isEmpty());
    }
}
