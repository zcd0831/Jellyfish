package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionDecidedEvent} 的单元测试：验证业务字段（含判定来源）与公共元信息。
 *
 * @author zcd
 */
class PermissionDecidedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        PermissionDecidedEvent event = new PermissionDecidedEvent("agent-a", "write_file",
                PermissionDecision.Outcome.DENY, "只读模式下仅允许白名单内的工具", "core", "session-1");

        // Then
        assertEquals("agent-a", event.getAgentId());
        assertEquals("write_file", event.getToolName());
        assertEquals(PermissionDecision.Outcome.DENY, event.getOutcome());
        assertEquals("只读模式下仅允许白名单内的工具", event.getReason());
        assertEquals("core", event.getSource());
        assertEquals("session-1", event.getSessionId());
    }

    @Test
    void getters_should_keep_plugin_source_when_intercepted_by_plugin() {
        // Given
        PermissionDecidedEvent event = new PermissionDecidedEvent("agent-a", "bash",
                PermissionDecision.Outcome.DENY, "危险命令", "guard-plugin", null);

        // Then
        assertEquals("guard-plugin", event.getSource());
        assertNull(event.getSessionId());
    }

    @Test
    void meta_should_be_filled_when_constructed() {
        // Given
        PermissionDecidedEvent event = new PermissionDecidedEvent(null, "read_file",
                PermissionDecision.Outcome.ALLOW, null, "core", null);

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertNull(event.getAgentId());
        assertNull(event.getReason());
    }

    @Test
    void belongsToSession_should_match_session_id() {
        // Given
        PermissionDecidedEvent event = new PermissionDecidedEvent("agent-a", "read_file",
                PermissionDecision.Outcome.ALLOW, null, "core", "session-1");

        // Then
        assertTrue(event.belongsToSession("session-1"));
    }
}
