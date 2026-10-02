package zcd.jellyfish.cli.mode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.SessionTestSupport;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.SubmissionPolicy;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.util.function.Consumer;

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
 * {@link CliRunMode} 的单元测试：验证「提交结果 + 终态事件 → stdout / stderr / 退出码」的映射。
 * <p>
 * <b>分流本身不在本测试范围</b>：命令判定、输入改写与输入指令的顺序是
 * {@code ConversationService} 的职责，在 core 模块测；这里只钉住外壳这一侧：
 * 拿到每种 {@link Submission.Kind} 与每种终态 {@link ShellTurnEvent.Kind} 之后写了什么、返回哪个退出码。
 * <p>
 * <b>事件怎么产生</b>：由被 mock 的 {@code ConversationService} 在 {@code submit} 里同步发布到真的
 * {@link ShellStreams} 上——而 CLI 的订阅在 {@code submit} 之前就已建立，因此这条路径与真实一致。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class CliRunModeTest {

    @Mock
    private ConversationService conversations;

    @Mock
    private SessionManager sessions;

    private Session session;

    private String sessionId;

    private RecordingConsoleIO console;

    /** 真可靠 lane：外壳在 submit 之前订阅它，mock 在 submit 内发布。 */
    private ShellStreams streams;

    private CliRunMode mode;

    @BeforeEach
    void setUp() {
        console = new RecordingConsoleIO(null);
        session = SessionTestSupport.newSession();
        sessionId = session.getSessionId();
        streams = new ShellStreams(new ShellIngress(new MetricsRegistry()));
        mode = new CliRunMode(conversations, streams, sessions, console);
    }

    /**
     * 桩：一次提交起回合，并在提交时同步发布事件（STARTED 已自动发布）。
     *
     * @param message 消息
     * @param emit    额外发布的事件，可为 {@code null}
     */
    private void givenTurn(String message, Consumer<ShellStreams> emit) {
        when(conversations.submit(eq(sessionId), eq(message), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class))).thenAnswer(invocation -> {
                    streams.publish(ShellTurnEvent.started(sessionId, "t1"));
                    if (emit != null) {
                        emit.accept(streams);
                    }
                    return Submission.turn(sessionId, "t1");
                });
    }

    @Test
    void run_should_return_ok_when_turn_completes() {
        givenCurrentSession();
        givenTurn("你好", lane -> lane.publish(ShellTurnEvent.completed(sessionId, "t1", "你好呀", 1, false)));

        int code = mode.run(options("你好"));

        assertEquals(ExitCodes.OK, code);
    }

    @Test
    void run_should_exit_ok_and_write_output_when_command_executed() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/help"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId, CommandResult.ok("帮助文本")));

        int code = mode.run(options("/help"));

        assertEquals(ExitCodes.OK, code);
        assertEquals("帮助文本\n", console.out());
    }

    @Test
    void run_should_keep_command_output_ending_intact() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/x"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId, CommandResult.ok("多行\n")));

        mode.run(options("/x"));

        assertEquals("多行\n", console.out());
    }

    @Test
    void run_should_write_nothing_when_command_has_no_output() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/silent"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId, CommandResult.ok(null)));

        int code = mode.run(options("/silent"));

        assertEquals(ExitCodes.OK, code);
        assertEquals("", console.out());
        assertEquals("", console.err());
    }

    @Test
    void run_should_return_ok_when_command_unknown() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/nosuch"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId,
                        CommandResult.unknown("未知命令：/nosuch（输入 /help 查看可用命令）")));

        int code = mode.run(options("/nosuch"));

        assertEquals(ExitCodes.OK, code);
        assertTrue(console.out().contains("未知命令"));
        assertEquals("", console.err());
    }

    @Test
    void run_should_return_runtime_error_and_use_stderr_when_command_failed() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/resume"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId, CommandResult.error("会话不存在：x")));

        int code = mode.run(options("/resume"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
        assertEquals("会话不存在：x\n", console.err());
        assertEquals("", console.out());
    }

    @Test
    void run_should_fall_back_to_generic_message_when_command_error_has_no_output() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("/x"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.command(sessionId, CommandResult.error(null)));

        mode.run(options("/x"));

        assertEquals("命令执行失败。\n", console.err());
    }

    @Test
    void run_should_write_notice_and_skip_turn_when_input_handled() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("?help"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.handled(sessionId, "先看看这份清单"));

        int code = mode.run(options("?help"));

        assertEquals(ExitCodes.OK, code);
        assertTrue(console.out().contains("先看看这份清单"), console.out());
    }

    @Test
    void run_should_fall_back_to_generic_notice_when_plugin_gives_none() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("?x"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.handled(sessionId, null));

        mode.run(options("?x"));

        assertTrue(console.out().contains("输入已被插件接过去"), console.out());
    }

    @Test
    void run_should_return_usage_error_when_prompt_blank() {
        int code = mode.run(options("   "));

        assertEquals(ExitCodes.USAGE_ERROR, code);
        assertTrue(console.err().contains("没有输入"));
        verify(conversations, never()).submit(any(), any(), any(), any());
    }

    @Test
    void run_should_return_usage_error_when_stdin_blank() {
        CliRunMode stdinMode = new CliRunMode(conversations, streams, sessions, new RecordingConsoleIO("  \n"));

        int code = stdinMode.run(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertEquals(ExitCodes.USAGE_ERROR, code);
    }

    @Test
    void run_should_read_stdin_when_prompt_absent() {
        givenCurrentSession();
        RecordingConsoleIO stdinConsole = new RecordingConsoleIO("来自管道\n");
        CliRunMode stdinMode = new CliRunMode(conversations, streams, sessions, stdinConsole);
        when(conversations.submit(eq(sessionId), eq("来自管道\n"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class))).thenAnswer(invocation -> {
                    streams.publish(ShellTurnEvent.started(sessionId, "t1"));
                    streams.publish(ShellTurnEvent.completed(sessionId, "t1", "收到", 1, false));
                    return Submission.turn(sessionId, "t1");
                });

        int code = stdinMode.run(StartupOptions.builder(StartupOptions.Mode.CLI).build());

        assertEquals(ExitCodes.OK, code);
    }

    @Test
    void run_should_return_runtime_error_when_turn_failed_without_listener_report() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("boom"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class))).thenThrow(new JellyfishException("流被中断"));

        int code = mode.run(options("boom"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
        assertTrue(console.err().contains("流被中断"));
    }

    @Test
    void run_should_not_duplicate_error_when_listener_already_reported_it() {
        // 终态 ERROR 已经把原因写到 stderr；随后 submit 抛出时不应再打一遍
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("boom"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class))).thenAnswer(invocation -> {
                    streams.publish(ShellTurnEvent.started(sessionId, "t1"));
                    streams.publish(ShellTurnEvent.error(sessionId, "t1", new JellyfishException("模型调用失败")));
                    throw new JellyfishException("模型调用失败");
                });

        mode.run(options("boom"));

        assertEquals(1, console.errorLineCount());
    }

    @Test
    void run_should_return_truncated_when_turn_not_converged() {
        givenCurrentSession();
        givenTurn("长任务", lane -> lane.publish(
                ShellTurnEvent.completed(sessionId, "t1", "达到上限", 16, true)));

        int code = mode.run(options("长任务"));

        assertEquals(ExitCodes.TRUNCATED, code);
    }

    @Test
    void run_should_return_turn_blocked_when_plugin_blocks_turn() {
        // 被插件拦下不是运行失败：脚本对它的补救动作（改请求 / 找人确认）与对 4（看日志排故障）完全不同
        givenCurrentSession();
        givenTurn("提交", lane -> lane.publish(ShellTurnEvent.blocked(sessionId, "t1", "工作区不干净")));

        int code = mode.run(options("提交"));

        assertEquals(ExitCodes.TURN_BLOCKED, code);
        // stdout 是「回答」的通道，而被拦下的回合没有回答；理由由监听器写进 stderr
        assertTrue(console.out().isEmpty());
        assertTrue(console.err().contains("回合被拦下"));
    }

    @Test
    void run_should_return_runtime_error_when_turn_cancelled() {
        givenCurrentSession();
        givenTurn("取消", lane -> lane.publish(ShellTurnEvent.cancelled(sessionId, "t1")));

        int code = mode.run(options("取消"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
    }

    @Test
    void run_should_read_current_session_each_time() {
        givenCurrentSession();
        givenTurn("你好", lane -> lane.publish(ShellTurnEvent.completed(sessionId, "t1", "ok", 1, false)));

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
        givenTurn("你好", lane -> {
            lane.publish(ShellTurnEvent.thinking(sessionId, "t1", "思考中"));
            lane.publish(ShellTurnEvent.completed(sessionId, "t1", "ok", 1, false));
        });

        mode.run(options("你好", true));

        assertTrue(console.err().contains("· 思考中"));
    }

    @Test
    void run_should_hide_thinking_by_default() {
        givenCurrentSession();
        givenTurn("你好", lane -> {
            lane.publish(ShellTurnEvent.thinking(sessionId, "t1", "思考中"));
            lane.publish(ShellTurnEvent.completed(sessionId, "t1", "ok", 1, false));
        });

        mode.run(options("你好"));

        assertFalse(console.err().contains("思考中"));
    }

    @Test
    void run_should_pass_cli_policy_to_submit() {
        // CLI 的策略是「执行命令 + 不解析输入指令 + 必须有会话」：这里钉住外壳确实声明了它
        givenCurrentSession();
        givenTurn("你好", lane -> lane.publish(ShellTurnEvent.completed(sessionId, "t1", "ok", 1, false)));

        mode.run(options("你好"));

        verify(conversations).submit(eq(sessionId), eq("你好"), eq(InputTransformRequest.Source.CLI),
                eq(SubmissionPolicy.cli()));
    }

    @Test
    void run_should_return_runtime_error_when_submission_is_rejected() {
        givenCurrentSession();
        when(conversations.submit(eq(sessionId), eq("你好"), eq(InputTransformRequest.Source.CLI),
                any(SubmissionPolicy.class)))
                .thenReturn(Submission.rejected(null, Submission.RejectReason.NO_SESSION));

        int code = mode.run(options("你好"));

        assertEquals(ExitCodes.RUNTIME_ERROR, code);
    }

    @Test
    void constructor_should_reject_null_collaborators() {
        assertThrows(NullPointerException.class, () -> new CliRunMode(null, streams, sessions, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(conversations, null, sessions, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(conversations, streams, null, console));
        assertThrows(NullPointerException.class, () -> new CliRunMode(conversations, streams, sessions, null));
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
