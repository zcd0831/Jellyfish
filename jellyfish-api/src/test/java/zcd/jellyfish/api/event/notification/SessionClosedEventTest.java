package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionClosedEvent} 的单元测试：验证关闭时的快照字段与公共元信息。
 *
 * @author zcd
 */
class SessionClosedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // When
        SessionClosedEvent event = new SessionClosedEvent("session-1", "coder", 3);

        // Then
        assertEquals("coder", event.getAgentId());
        assertEquals(3, event.getMessageCount());
        assertEquals("session-1", event.getSessionId());
    }

    @Test
    void getAgentId_should_return_null_when_agent_unbound() {
        // When
        SessionClosedEvent event = new SessionClosedEvent("session-1", null, 0);

        // Then
        assertNull(event.getAgentId());
        assertEquals(0, event.getMessageCount());
    }

    @Test
    void meta_should_be_filled_and_match_session_when_constructed() {
        // When
        SessionClosedEvent event = new SessionClosedEvent("session-1", "coder", 3);

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertTrue(event.belongsToSession("session-1"));
    }
}
