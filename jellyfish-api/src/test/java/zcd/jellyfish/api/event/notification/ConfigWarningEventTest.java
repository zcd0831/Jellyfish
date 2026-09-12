package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigWarningEvent} 的单元测试：验证业务字段与公共元信息。
 *
 * @author zcd
 */
class ConfigWarningEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent("path", "message");

        // Then
        assertEquals("path", event.getSource());
        assertEquals("message", event.getMessage());
    }

    @Test
    void getSource_should_return_null_when_not_provided() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent(null, "message");

        // Then
        assertNull(event.getSource());
    }

    @Test
    void meta_should_be_filled_when_constructed() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent("path", "message");

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertNull(event.getSessionId());
    }

    @Test
    void getSessionId_should_return_session_when_provided() {
        // Given
        ConfigWarningEvent event = new ConfigWarningEvent("path", "message", "session-1");

        // Then
        assertEquals("session-1", event.getSessionId());
        assertTrue(event.belongsToSession("session-1"));
    }
}
