package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CommandRequest} 的单元测试：验证命令名、参数归一化、会话透传与结果类型。
 *
 * @author zcd
 */
class CommandRequestTest {

    @Test
    void getRouteKey_should_return_command_name() {
        // Given
        CommandRequest request = new CommandRequest("sql:query", CommandArguments.EMPTY, "session-1");

        // Then
        assertEquals("sql:query", request.getRouteKey());
        assertEquals("sql:query", request.getName());
    }

    @Test
    void getArguments_should_return_constructed_arguments() {
        // Given
        CommandArguments arguments = new CommandArguments(Arrays.asList("coder"), "coder");

        // When
        CommandRequest request = new CommandRequest("agent", arguments, "session-1");

        // Then
        assertSame(arguments, request.getArguments());
        assertEquals("coder", request.getArguments().getTokens().get(0));
        assertEquals("session-1", request.getSessionId());
    }

    @Test
    void constructor_should_normalize_null_arguments() {
        // When
        CommandRequest request = new CommandRequest("help", null);

        // Then
        assertSame(CommandArguments.EMPTY, request.getArguments());
    }

    @Test
    void getResultType_should_be_command_result() {
        // Given：结果类型由内核在 invoke 时兜底校验，因此必须是 CommandResult 而不是 Object
        CommandRequest request = new CommandRequest("help", CommandArguments.EMPTY);

        // Then
        assertEquals(CommandResult.class, request.getResultType());
    }

    @Test
    void constructor_should_throw_when_name_is_blank() {
        assertThrows(JellyfishException.class, () -> new CommandRequest(null, CommandArguments.EMPTY));
        assertThrows(JellyfishException.class, () -> new CommandRequest("  ", CommandArguments.EMPTY));
    }
}
