package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventBus} 的单元测试：使用「当前线程直接执行」的派发器获得同步语义。
 *
 * @author zcd
 */
class EventBusTest {

    /** 被测事件总线。 */
    private final EventBus eventBus = new EventBus(Runnable::run);

    @Test
    void subscribe_should_receive_published_event() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        eventBus.subscribe(ConfigWarningEvent.class, received::add);

        // When
        eventBus.publish(new ConfigWarningEvent("source", "message"));

        // Then
        assertEquals(1, received.size());
        assertEquals("source", received.get(0).getSource());
    }

    @Test
    void publish_should_receive_event_when_subscribed_to_parent_type() {
        // Given
        List<Object> received = new ArrayList<>();
        eventBus.subscribe(Object.class, received::add);

        // When
        eventBus.publish(new ConfigWarningEvent("source", "message"));

        // Then
        assertEquals(1, received.size());
    }

    @Test
    void unsubscribe_should_stop_receiving_events() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        EventListener<ConfigWarningEvent> listener = received::add;
        eventBus.subscribe(ConfigWarningEvent.class, listener);

        // When
        eventBus.unsubscribe(ConfigWarningEvent.class, listener);
        eventBus.publish(new ConfigWarningEvent("source", "message"));

        // Then
        assertTrue(received.isEmpty());
    }

    @Test
    void publish_should_throw_when_event_is_null() {
        assertThrows(JellyfishException.class, () -> eventBus.publish(null));
    }

    @Test
    void subscribe_should_throw_when_listener_is_null() {
        assertThrows(JellyfishException.class, () -> eventBus.subscribe(ConfigWarningEvent.class, null));
    }

    @Test
    void dispatch_should_keep_calling_other_listeners_when_one_fails() {
        // Given
        List<ConfigWarningEvent> received = new ArrayList<>();
        eventBus.subscribe(ConfigWarningEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        eventBus.subscribe(ConfigWarningEvent.class, received::add);

        // When / Then
        assertThrows(JellyfishException.class, () -> eventBus.publish(new ConfigWarningEvent("source", "message")));
        assertEquals(1, received.size());
    }
}
