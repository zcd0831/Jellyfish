package zcd.jellyfish.infra.event.notification;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventRegistration} 的单元测试：验证字段透出、过滤判定与监听器调用。
 *
 * @author zcd
 */
class EventRegistrationTest {

    @Test
    void getters_and_accepts_should_expose_constructed_values() {
        // Given
        EventRegistration registration = new EventRegistration("plugin-a", ConfigWarningEvent.class,
                event -> true, event -> {
                    // 仅用于校验字段透出
                });
        ConfigWarningEvent event = new ConfigWarningEvent("path", "message");

        // Then
        assertEquals("plugin-a", registration.getOwner());
        assertEquals(ConfigWarningEvent.class, registration.getEventType());
        assertTrue(registration.accepts(event));
    }

    @Test
    void accepts_should_return_false_when_filter_rejects() {
        // Given
        EventRegistration registration = new EventRegistration("plugin-a", ConfigWarningEvent.class,
                event -> false, event -> {
                    // 仅用于校验过滤判定
                });

        // Then
        assertFalse(registration.accepts(new ConfigWarningEvent("path", "message")));
    }

    @Test
    void invoke_should_call_listener_with_event() {
        // Given
        List<JellyfishEvent> received = new ArrayList<>();
        Consumer<JellyfishEvent> listener = received::add;
        EventRegistration registration = new EventRegistration("plugin-a", ConfigWarningEvent.class,
                event -> true, listener);

        // When
        registration.invoke(new ConfigWarningEvent("path", "message"));

        // Then
        assertEquals(1, received.size());
    }
}
