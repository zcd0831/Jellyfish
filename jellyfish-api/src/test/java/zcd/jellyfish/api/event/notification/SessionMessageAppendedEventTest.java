package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionMessageAppendedEvent} 的单元测试：验证字段透传、正文不入事件与公共元信息。
 *
 * @author zcd
 */
class SessionMessageAppendedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // When
        SessionMessageAppendedEvent event = new SessionMessageAppendedEvent("session-1", "message-1", "user");

        // Then
        assertEquals("message-1", event.getMessageId());
        assertEquals("user", event.getRole());
        assertEquals("session-1", event.getSessionId());
    }

    @Test
    void meta_should_be_filled_and_match_session_when_constructed() {
        // When
        SessionMessageAppendedEvent event = new SessionMessageAppendedEvent("session-1", "message-1", "assistant");

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertTrue(event.belongsToSession("session-1"));
    }
}
