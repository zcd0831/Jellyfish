package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.command.ToolCallCommand;
import zcd.jellyfish.api.event.command.ToolCallResult;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CommandReplies} 的单元测试：验证应答槽的回填、类型校验、异常回传与回收。
 *
 * @author zcd
 */
class CommandRepliesTest {

    /** 被测应答槽。 */
    private final CommandReplies replies = new CommandReplies();

    @Test
    void await_should_return_result_when_completed() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        replies.complete(command, new ToolCallResult("calculator", 42));

        // Then
        ToolCallResult result = replies.await(command);
        assertEquals("calculator", result.getToolName());
    }

    @Test
    void complete_should_throw_when_result_type_mismatch() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        CommandException exception = assertThrows(CommandException.class, () -> replies.complete(command, "not-a-result"));

        // Then
        assertEquals(CommandException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void await_should_throw_no_response_when_not_answered() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        CommandException exception = assertThrows(CommandException.class, () -> replies.await(command));

        // Then
        assertEquals(CommandException.Code.NO_RESPONSE, exception.getCode());
    }

    @Test
    void await_should_throw_no_response_when_slot_missing() {
        // Given
        ToolCallCommand command = toolCall();

        // When
        CommandException exception = assertThrows(CommandException.class, () -> replies.await(command));

        // Then
        assertEquals(CommandException.Code.NO_RESPONSE, exception.getCode());
    }

    @Test
    void await_should_rethrow_runtime_exception_when_failed() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        replies.fail(command, new IllegalStateException("boom"));

        // Then
        assertThrows(IllegalStateException.class, () -> replies.await(command));
    }

    @Test
    void await_should_wrap_checked_exception_when_failed() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        replies.fail(command, new Exception("checked"));

        // Then
        JellyfishException exception = assertThrows(JellyfishException.class, () -> replies.await(command));
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void await_should_rethrow_error_when_failed_with_error() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        replies.fail(command, new AssertionError("fatal"));

        // Then
        assertThrows(AssertionError.class, () -> replies.await(command));
    }

    @Test
    void await_should_throw_no_response_after_close() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);
        replies.complete(command, new ToolCallResult("calculator", 42));

        // When
        replies.close(command);

        // Then
        assertThrows(CommandException.class, () -> replies.await(command));
    }

    @Test
    void complete_should_allow_result_of_any_type_when_result_type_is_object() {
        // Given
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        replies.open(command);

        // When
        replies.complete(command, "anything");

        // Then
        assertEquals("anything", replies.await(command));
    }

    /**
     * 构造工具调用命令。
     *
     * @return 工具调用命令
     */
    private static ToolCallCommand toolCall() {
        return new ToolCallCommand("calculator", Collections.<String, Object>emptyMap(), null, 0L);
    }
}
