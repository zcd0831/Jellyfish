package zcd.jellyfish.cli.mode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.SessionTestSupport;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CliRunMode} 的单元测试：验证输入来源、命令与 LLM 的分流、命令三态与回合三态的退出码。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class CliRunModeTest {

    @Mock
    private AgentHarness harness;

    @Mock
    private CommandManager commands;

    @Mock
    private SessionManager sessions;

    @Mock
    private ReActTurn turn;

    private Session session;

    private String sessionId;

    private RecordingConsoleIO console;

    private CliRunMode mode;

    @BeforeEach
    void setUp() {
        console = new RecordingConsoleIO(null);
        session = SessionTestSupport.newSession();
        sessionId = session.getSessionId();
        mode = new CliRunMode(harness, commands, sessions, console);
    }

    @Test
    void isImplemented_should_return_true() {
        assertTrue(mode.isImplemented());
    }

    @Test
    void run_should_chat_when_input_is_not_command() {
        givenCurrentSession();
        when(commands.isCommand("你好")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("你好"), any())).thenReturn(turn);
        when(turn.await()).thenReturn(ReActResult.completed(sessionId, "你好呀", 1));

        int code = mode.run(options("你好"));

        assertEquals(ExitCodes.OK, code);
        verify(harness).chat(eq(sessionId), eq("你好"), any());
        verify(commands, never()).execute(any(), any());
    }

    @Test
    void run_should_execute_command_and_not_chat_when_input_is_command() {
        givenCurrentSession();
        when(commands.isCommand("/help")).thenReturn(true);
        when(commands.execute("/help", sessionId)).thenReturn(CommandResult.ok("帮助文本"));

        int code = mode.run(options("/help"));

        assertEquals(ExitCodes.OK, code);
        assertEquals("帮助文本\n", console.out());
        verify(harness, never()).chat(any(), any(), any());
    }

    @Test
    void run_should_keep_command_output_ending_intact() {
        givenCurrentSession();
        when(commands.isCommand("/x")).thenReturn(true);
        when(commands.execute("/x", sessionId)).thenReturn(CommandResult.ok("多行\n"));

        mode.run(options("/x"));

        assertEquals("多行\n", console.out());
    }

    @Test
    void run_should_write_nothing_when_command_has_no_output() {
        givenCurrentSession();
        when(commands.isCommand("/silent")).thenReturn(true);
        when(commands.execute("/silent", sessionId)).thenReturn(CommandResult.ok(null));

        int code = mode.run(options("/silent"));

        assertEquals(ExitCodes.OK, code);
        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void run_should_return_ok_when_command_unknown() {
        givenCurrentSession();
        when(commands.isCommand("/nosuch")).thenReturn(true);
        when(commands.execute("/nosuch", sessionId))
                .thenReturn(CommandResult.unknown("未知命令：/nosuch（输入 /help 查看可用命令）"));

        int code = mode.run(options("/nosuch"));

        assertEquals(ExitCodes.OK, code);
        assertTrue(console.out().contains("未知命令"));
        assertEquals("", console.err());
    }

    @Test
    void run_should_return_runtime_error_and_use_stderr_when_command_failed() {
        givenCurrentSession();
        when(commands.isCommand("/resume")).thenReturn(true);
        when(commands.execute("/resume", sessionId)).thenReturn(CommandResult.error("会话不存在：x"));

        int code = mode.run(options("/resume"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
        assertEquals("会话不存在：x\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void run_should_fall_back_to_generic_message_when_command_error_has_no_output() {
        givenCurrentSession();
        when(commands.isCommand("/x")).thenReturn(true);
        when(commands.execute("/x", sessionId)).thenReturn(CommandResult.error(null));

        mode.run(options("/x"));

        assertEquals("命令执行失败。\n", console.err());
    }

    @Test
    void run_should_return_usage_error_when_prompt_blank() {
        int code = mode.run(options("   "));

        assertEquals(ExitCodes.USAGE_ERROR, code);
        assertTrue(console.err().contains("没有输入"));
        verify(harness, never()).chat(any(), any(), any());
    }

    @Test
    void run_should_return_usage_error_when_stdin_blank() {
        CliRunMode stdinMode = new CliRunMode(harness, commands, sessions, new RecordingConsoleIO("  \n"));

        int code = stdinMode.run(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertEquals(ExitCodes.USAGE_ERROR, code);
    }

    @Test
    void run_should_read_stdin_when_prompt_absent() {
        givenCurrentSession();
        CliRunMode stdinMode = new CliRunMode(harness, commands, sessions, new RecordingConsoleIO("来自管道\n"));
        when(commands.isCommand("来自管道\n")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("来自管道\n"), any())).thenReturn(turn);
        when(turn.await()).thenReturn(ReActResult.completed(sessionId, "收到", 1));

        int code = stdinMode.run(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertEquals(ExitCodes.OK, code);
        verify(harness).chat(eq(sessionId), eq("来自管道\n"), any());
    }

    @Test
    void run_should_return_runtime_error_when_turn_failed() {
        givenCurrentSession();
        when(commands.isCommand("boom")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("boom"), any())).thenReturn(turn);
        when(turn.await()).thenThrow(new JellyfishException("模型调用失败"));

        int code = mode.run(options("boom"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
        assertTrue(console.err().contains("模型调用失败"));
    }

    @Test
    void run_should_not_duplicate_error_when_listener_already_reported_it() {
        givenCurrentSession();
        when(commands.isCommand("boom")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("boom"), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, zcd.jellyfish.core.ReActListener.class)
                    .onError(new JellyfishException("模型调用失败"));
            return turn;
        });
        when(turn.await()).thenThrow(new JellyfishException("模型调用失败"));

        mode.run(options("boom"));

        assertEquals(1, console.errorLineCount());
    }

    @Test
    void run_should_return_truncated_when_turn_not_converged() {
        givenCurrentSession();
        when(commands.isCommand("长任务")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("长任务"), any())).thenReturn(turn);
        when(turn.await()).thenReturn(ReActResult.truncated(sessionId, "达到上限", 16));

        int code = mode.run(options("长任务"));

        // 截断提示由 listener.onComplete 负责（已在 CliReActListenerTest 覆盖），这里只钉退出码
        assertEquals(ExitCodes.TRUNCATED, code);
    }

    @Test
    void run_should_return_runtime_error_when_turn_cancelled() {
        givenCurrentSession();
        when(commands.isCommand("取消")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("取消"), any())).thenReturn(turn);
        when(turn.await()).thenReturn(ReActResult.cancelled(sessionId, 1));

        int code = mode.run(options("取消"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
    }

    @Test
    void run_should_read_current_session_each_time() {
        givenCurrentSession();
        when(commands.isCommand("你好")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("你好"), any())).thenReturn(turn);
        when(turn.await()).thenReturn(ReActResult.completed(sessionId, "ok", 1));

        mode.run(options("你好"));

        verify(sessions).current();
    }

    @Test
    void run_should_fail_when_no_current_session_so_launcher_wiring_is_visible() {
        when(sessions.current()).thenReturn(null);

        assertThrows(JellyfishException.class, () -> mode.run(options("你好")));
    }

    @Test
    void run_should_enable_thinking_display_when_option_given() {
        givenCurrentSession();
        when(commands.isCommand("你好")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("你好"), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, zcd.jellyfish.core.ReActListener.class).onThinking("思考中");
            return turn;
        });
        when(turn.await()).thenReturn(ReActResult.completed(sessionId, "ok", 1));

        mode.run(options("你好", true));

        assertTrue(console.err().contains("· 思考中"));
    }

    @Test
    void run_should_hide_thinking_by_default() {
        givenCurrentSession();
        when(commands.isCommand("你好")).thenReturn(false);
        when(harness.chat(eq(sessionId), eq("你好"), any())).thenAnswer(invocation -> {
            invocation.getArgument(2, zcd.jellyfish.core.ReActListener.class).onThinking("思考中");
            return turn;
        });
        when(turn.await()).thenReturn(ReActResult.completed(sessionId, "ok", 1));

        mode.run(options("你好"));

        assertFalse(console.err().contains("思考中"));
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        assertThrows(NullPointerException.class, () -> new CliRunMode(null, commands, sessions, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(harness, null, sessions, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(harness, commands, null, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(harness, commands, sessions, null));
    }

    /**
     * 桩：当前会话为 {@code session-1}。
     */
    private void givenCurrentSession() {
        when(sessions.current()).thenReturn(session);
    }

    /**
     * 构造 CLI 单次模式参数。
     *
     * @param prompt 输入
     * @return 启动参数
     */
    private static StartupOptions options(String prompt) {
        return options(prompt, false);
    }

    /**
     * 构造 CLI 单次模式参数。
     *
     * @param prompt       输入
     * @param showThinking 是否显示思考过程
     * @return 启动参数
     */
    private static StartupOptions options(String prompt, boolean showThinking) {
        return StartupOptions.builder(StartupOptions.Mode.CLI)
                .prompt(prompt).showThinking(showThinking).build();
    }
}
