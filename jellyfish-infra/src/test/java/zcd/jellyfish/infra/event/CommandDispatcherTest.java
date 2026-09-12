package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.CommandHandler;
import zcd.jellyfish.api.event.command.PermissionCheckCommand;
import zcd.jellyfish.api.event.command.PermissionDecision;
import zcd.jellyfish.api.event.command.PluginCommand;
import zcd.jellyfish.api.event.command.ToolCallCommand;
import zcd.jellyfish.api.event.command.ToolCallResult;
import zcd.jellyfish.infra.event.command.CommandRegistry;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CommandDispatcher} 的单元测试：验证三类核心命令的注册表派发与失败记账。
 *
 * @author zcd
 */
class CommandDispatcherTest {

    /** 命令注册表。 */
    private final CommandRegistry registry = new CommandRegistry();

    /** 命令应答槽。 */
    private final CommandReplies replies = new CommandReplies();

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** 被测命令分发器。 */
    private final CommandDispatcher dispatcher = new CommandDispatcher(registry, replies, stats);

    @Test
    void onToolCall_should_complete_result_and_count_dispatched() {
        // Given
        registry.register("builtin", false, ToolCallCommand.class, "calculator",
                command -> new ToolCallResult("calculator", 42), RegisterOptions.DEFAULT);
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        dispatcher.onToolCall(command);

        // Then
        assertEquals(42, replies.await(command).getOutput());
        assertEquals(1L, stats.getDispatchedCommands());
    }

    @Test
    void onPermissionCheck_should_complete_result_when_handler_registered() {
        // Given
        registry.register("builtin", false, PermissionCheckCommand.class, null,
                command -> PermissionDecision.allow("ok"), RegisterOptions.DEFAULT);
        PermissionCheckCommand command = new PermissionCheckCommand("agent", "calculator", null);
        replies.open(command);

        // When
        dispatcher.onPermissionCheck(command);

        // Then
        assertEquals(true, replies.await(command).isGranted());
        assertEquals(1L, stats.getDispatchedCommands());
    }

    @Test
    void onPluginCommand_should_complete_result_when_handler_registered() {
        // Given
        registry.register("builtin", false, PluginCommand.class, "calculator", command -> "42",
                RegisterOptions.DEFAULT);
        PluginCommand command = new PluginCommand("calculator", Object.class, null);
        replies.open(command);

        // When
        dispatcher.onPluginCommand(command);

        // Then
        assertEquals("42", replies.await(command));
        assertEquals(1L, stats.getDispatchedCommands());
    }

    @Test
    void onToolCall_should_fail_with_no_handler_when_not_registered() {
        // Given
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        dispatcher.onToolCall(command);

        // Then
        CommandException exception = assertThrows(CommandException.class, () -> replies.await(command));
        assertEquals(CommandException.Code.NO_HANDLER, exception.getCode());
        assertEquals(1L, stats.getNoHandlerCommands());
        assertEquals(1L, stats.getFailedCommands());
    }

    @Test
    void onToolCall_should_fail_with_ambiguous_handler_when_two_candidates_match() {
        // Given：类型唯一处理器与路由处理器同时命中
        registry.register("builtin", false, ToolCallCommand.class, null,
                command -> new ToolCallResult("calculator", 1), RegisterOptions.DEFAULT);
        registry.register("plugin-a", false, ToolCallCommand.class, "calculator",
                command -> new ToolCallResult("calculator", 2), RegisterOptions.DEFAULT);
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        dispatcher.onToolCall(command);

        // Then
        CommandException exception = assertThrows(CommandException.class, () -> replies.await(command));
        assertEquals(CommandException.Code.AMBIGUOUS_HANDLER, exception.getCode());
        assertEquals(1L, stats.getAmbiguousHandlerCommands());
    }

    @Test
    void onToolCall_should_rethrow_and_count_when_handler_throws() {
        // Given
        registry.register("builtin", false, ToolCallCommand.class, "calculator", command -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.DEFAULT);
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        dispatcher.onToolCall(command);

        // Then
        assertThrows(IllegalStateException.class, () -> replies.await(command));
        assertEquals(1L, stats.getFailedCommands());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onToolCall_should_fail_with_result_type_mismatch_when_handler_returns_wrong_type() {
        // Given：用原始类型绕过编译期泛型，模拟处理器返回与 resultType 不符的对象
        registry.register("builtin", false, ToolCallCommand.class, "calculator",
                (CommandHandler) command -> "not-a-tool-call-result", RegisterOptions.DEFAULT);
        ToolCallCommand command = toolCall();
        replies.open(command);

        // When
        dispatcher.onToolCall(command);

        // Then
        CommandException exception = assertThrows(CommandException.class, () -> replies.await(command));
        assertEquals(CommandException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
        assertEquals(1L, stats.getFailedCommands());
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
