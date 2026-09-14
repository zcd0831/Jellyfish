package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionCreatedEvent} 的单元测试：验证字段透传与公共元信息。
 *
 * @author zcd
 */
class SessionCreatedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // When
        SessionCreatedEvent event = new SessionCreatedEvent("coder", "session-1");

        // Then
        assertEquals("coder", event.getAgentId());
        assertEquals("session-1", event.getSessionId());
    }

    @Test
    void getAgentId_should_return_null_when_agent_unbound() {
        // When
        SessionCreatedEvent event = new SessionCreatedEvent(null, "session-1");

        // Then
        assertNull(event.getAgentId());
    }

    @Test
    void meta_should_be_filled_and_match_session_when_constructed() {
        // When
        SessionCreatedEvent event = new SessionCreatedEvent("coder", "session-1");

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertTrue(event.belongsToSession("session-1"));
    }

    @Test
    void belongsToSession_should_return_false_when_other_session() {
        // Given
        SessionCreatedEvent event = new SessionCreatedEvent("coder", "session-1");

        // When / Then
        assertFalse(event.belongsToSession("session-2"));
        assertFalse(event.belongsToSession(null));
    }
}
