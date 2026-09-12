package zcd.jellyfish.infra.event;

import com.google.common.eventbus.DeadEvent;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.infra.event.notification.EventRegistry;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link EventDispatcher} 的单元测试：验证各核心通知类型都被转交给细粒度注册表广播。
 *
 * @author zcd
 */
class EventDispatcherTest {

    /** 通知注册表。 */
    private final EventRegistry registry = new EventRegistry();

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** 被测通知分发器。 */
    private final EventDispatcher dispatcher = new EventDispatcher(registry, stats);

    @Test
    void onConfigWarning_should_dispatch_to_registry() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When
        dispatcher.onConfigWarning(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void onToolCallStarted_should_dispatch_to_registry() {
        // Given
        List<ToolCallStartedEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ToolCallStartedEvent.class, null, received::add);

        // When
        dispatcher.onToolCallStarted(new ToolCallStartedEvent("call-1", "calculator", null));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void onToolCallCompleted_should_dispatch_to_registry() {
        // Given
        List<ToolCallCompletedEvent> received = new ArrayList<>();
        registry.subscribe("metrics", ToolCallCompletedEvent.class, null, received::add);

        // When
        dispatcher.onToolCallCompleted(new ToolCallCompletedEvent("call-1", "calculator", true, 5L, null, null));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void onSessionCreated_should_dispatch_to_registry() {
        // Given
        List<SessionCreatedEvent> received = new ArrayList<>();
        registry.subscribe("metrics", SessionCreatedEvent.class, null, received::add);

        // When
        dispatcher.onSessionCreated(new SessionCreatedEvent("agent-a", "session-1"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void onPluginStateChanged_should_dispatch_to_registry() {
        // Given
        List<PluginStateChangedEvent> received = new ArrayList<>();
        registry.subscribe("metrics", PluginStateChangedEvent.class, null, received::add);

        // When
        dispatcher.onPluginStateChanged(new PluginStateChangedEvent("plugin-a", "STARTED"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void onPluginNotification_should_dispatch_to_registry() {
        // Given
        List<PluginNotificationEvent> received = new ArrayList<>();
        registry.subscribe("metrics", PluginNotificationEvent.class, null, received::add);

        // When
        dispatcher.onPluginNotification(new PluginNotificationEvent("plugin-a", "payload", null));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void dispatch_should_count_unmatched_when_no_subscriber_hits() {
        // When
        dispatcher.onConfigWarning(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1L, stats.getUnmatchedNotifications());
    }

    @Test
    void dispatch_should_count_subscriber_errors_and_keep_broadcasting() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        registry.subscribe("broken", ConfigWarningEvent.class, null, event -> {
            throw new IllegalStateException("boom");
        });
        registry.subscribe("metrics", ConfigWarningEvent.class, null, received::add);

        // When
        dispatcher.onConfigWarning(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
        assertEquals(1L, stats.getSubscriberErrors());
    }

    @Test
    void onDeadEvent_should_count_dead_event_type() {
        // Given
        DeadEvent deadEvent = new DeadEvent(this, new ConfigWarningEvent("path", "message"));

        // When
        dispatcher.onDeadEvent(deadEvent);

        // Then
        assertEquals(1L, stats.getDeadEventTypes());
    }
}
