package zcd.jellyfish.api.event.notification;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandExecutedEvent} 的单元测试：验证审计字段与公共元信息。
 *
 * @author zcd
 */
class CommandExecutedEventTest {

    @Test
    void getters_should_return_constructor_values() {
        // Given
        CommandExecutedEvent event = new CommandExecutedEvent("/a coder", "a", "agent", CommandResult.Kind.OK,
                "core", 12L, "session-1");

        // Then
        assertEquals("/a coder", event.getInput());
        assertEquals("a", event.getName());
        assertEquals("agent", event.getCanonicalName());
        assertEquals(CommandResult.Kind.OK, event.getKind());
        assertEquals("core", event.getSource());
        assertEquals(12L, event.getDurationMillis());
        assertEquals("session-1", event.getSessionId());
    }

    @Test
    void getters_should_allow_unknown_command_without_canonical_or_source() {
        // Given：未知命令没有命中任何处理器
        CommandExecutedEvent event = new CommandExecutedEvent("/ghost", "ghost", null, CommandResult.Kind.UNKNOWN,
                null, 0L, null);

        // Then
        assertNull(event.getCanonicalName());
        assertNull(event.getSource());
        assertNull(event.getSessionId());
        assertEquals(CommandResult.Kind.UNKNOWN, event.getKind());
    }

    @Test
    void meta_should_be_filled_when_constructed() {
        // Given
        CommandExecutedEvent event = new CommandExecutedEvent(null, "help", "help", CommandResult.Kind.OK,
                "core", 1L, null);

        // Then
        assertNotNull(event.getEventId());
        assertTrue(event.getOccurredAt() > 0L);
        assertNull(event.getInput());
    }

    @Test
    void belongsToSession_should_match_session_id() {
        // Given
        CommandExecutedEvent event = new CommandExecutedEvent("/help", "help", "help", CommandResult.Kind.OK,
                "core", 1L, "session-1");

        // Then
        assertTrue(event.belongsToSession("session-1"));
    }
}
