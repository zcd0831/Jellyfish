package zcd.jellyfish.core.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.core.input.InputDirectiveRun;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.input.InputTransforms;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationService} 的顺序不变量与策略分流。
 * <p>
 * 本测试就是 P0 的核心：分流顺序此前在三个外壳各写一份并已漂移，现在它是内核不变量，
 * 因此每一条顺序都要有一条用例钉住——顺序错的表现是「某条路径悄悄不生效」，回归里最难发现。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private CommandManager commands;

    @Mock
    private InputTransforms inputTransforms;

    @Mock
    private InputDirectives inputDirectives;

    @Mock
    private SessionManager sessions;

    @Mock
    private AgentHarness harness;

    @Mock
    private ReActTurn turn;

    @Mock
    private InputDirectiveRun directiveRun;

    /** 真实的在途回合表：它的并发约束就是本服务的顺序不变量的一部分。 */
    private TurnRegistry turnRegistry;

    /** 真实可靠 lane：本测试顺便钉住「事件被发布出来」这件事。 */
    private ShellStreams streams;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        turnRegistry = new TurnRegistry(ignored -> { });
        streams = new ShellStreams(new ShellIngress(new MetricsRegistry()));
        service = new ConversationService(commands, inputTransforms, inputDirectives, sessions, turnRegistry,
                streams, harness);
    }

    /**
     * 让「起回合」这一步走通：输入改写放行、指令不认领、{@code harness.chat} 返回 mock 句柄。
     *
     * @param sessionId 会话标识
     * @param input     输入
     */
    private void givenTurnStarts(String sessionId, String input) {
        when(inputTransforms.transform(sessionId, input, InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(inputDirectives.submit(eq(sessionId), eq(input), any())).thenReturn(Optional.empty());
        when(harness.chat(eq(sessionId), anyString(), eq(input), any())).thenReturn(turn);
    }

    @Test
    void submit_should_reject_blank_input_without_touching_anything() {
        Submission submission = service.submit("s1", "   \n", InputTransformRequest.Source.CLI,
                SubmissionPolicy.cli());

        assertEquals(Submission.Kind.REJECTED, submission.getKind());
        assertEquals(Submission.RejectReason.BLANK_INPUT, submission.getRejectReason());
        verifyNoInteractions(commands, inputTransforms, inputDirectives, sessions, harness);
    }

    @Test
    void submit_should_treat_null_text_as_blank() {
        Submission submission = service.submit("s1", null, InputTransformRequest.Source.CLI,
                SubmissionPolicy.cli());

        assertEquals(Submission.RejectReason.BLANK_INPUT, submission.getRejectReason());
    }

    @Test
    void submit_should_execute_command_and_skip_transform_when_command_matched() {
        // 顺序 1：命令判定先于输入改写——插件改不动用户显式敲下的命令
        when(commands.shouldRunAsCommand("/help", true)).thenReturn(true);
        when(commands.execute("/help", "s1")).thenReturn(CommandResult.ok("帮助"));

        Submission submission = service.submit("s1", "/help", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.EXECUTED_COMMAND, submission.getKind());
        assertEquals("帮助", submission.getCommandResult().getOutput());
        verifyNoInteractions(inputTransforms, inputDirectives, harness);
    }

    @Test
    void submit_should_not_execute_command_when_policy_disables_commands() {
        // 顺序 6：commands=false 时「看起来像命令」按普通文本处理，不报错也不静默丢
        when(inputTransforms.transform("s1", "/help", InputTransformRequest.Source.SERVER))
                .thenReturn(InputTransformResult.continueAsIs());
        when(harness.chat(eq("s1"), anyString(), eq("/help"), any())).thenReturn(turn);

        Submission submission = service.submit("s1", "/help", InputTransformRequest.Source.SERVER,
                SubmissionPolicy.serverChat());

        assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
        verify(commands, never()).shouldRunAsCommand(anyString(), anyBoolean());
        verify(commands, never()).execute(anyString(), anyString());
    }

    @Test
    void submit_should_report_handled_input_and_skip_everything_after_transform() {
        when(commands.shouldRunAsCommand("?help", true)).thenReturn(false);
        when(inputTransforms.transform("s1", "?help", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.handled("先看看这份清单"));

        Submission submission = service.submit("s1", "?help", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.HANDLED_INPUT, submission.getKind());
        assertEquals("先看看这份清单", submission.getNotice());
        verifyNoInteractions(inputDirectives, sessions, harness);
    }

    @Test
    void submit_should_not_create_session_when_input_was_handled() {
        // 顺序 3：输入改写先于建会话——被接过去的输入不该留下一个空会话
        when(inputTransforms.transform(null, "?help", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.handled("已接管"));

        Submission submission = service.submit(null, "?help", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.HANDLED_INPUT, submission.getKind());
        verify(sessions, never()).createDefault();
        verify(sessions, never()).switchTo(anyString());
    }

    @Test
    void submit_should_use_replaced_text_for_directive_and_turn() {
        // 顺序 2：输入改写先于输入指令解析——指令按改写后的文本解析
        when(inputTransforms.transform("s1", "继续", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.replace("附上上下文：继续"));
        when(inputDirectives.submit(eq("s1"), eq("附上上下文：继续"), any()))
                .thenReturn(Optional.empty());
        when(harness.chat(eq("s1"), anyString(), eq("附上上下文：继续"), any())).thenReturn(turn);

        Submission submission = service.submit("s1", "继续", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
        assertNotNull(submission.getTurnId());
    }

    @Test
    void submit_should_start_directive_before_turn_when_directive_matched() {
        // 顺序 4：输入指令先于普通对话
        when(inputTransforms.transform("s1", "!ls", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(inputDirectives.submit(eq("s1"), eq("!ls"), any())).thenReturn(Optional.of(directiveRun));

        Submission submission = service.submit("s1", "!ls", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.STARTED_DIRECTIVE, submission.getKind());
        assertSame(directiveRun, submission.getDirectiveRun());
        verify(harness, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void submit_should_not_resolve_directive_when_policy_disables_directives() {
        // CLI 明确不启用输入指令（决策 D6）：即使插件认领，也必须按普通文本发给模型
        when(inputTransforms.transform("s1", "!ls", InputTransformRequest.Source.CLI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(harness.chat(eq("s1"), anyString(), eq("!ls"), any())).thenReturn(turn);

        Submission submission = service.submit("s1", "!ls", InputTransformRequest.Source.CLI,
                SubmissionPolicy.cli());

        assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
        verifyNoInteractions(inputDirectives);
    }

    @Test
    void submit_should_reject_without_session_when_policy_requires_existing() {
        // 顺序 5：REQUIRE_EXISTING 且无会话时当场拒绝，绝不建会话
        when(inputTransforms.transform(null, "你好", InputTransformRequest.Source.SERVER))
                .thenReturn(InputTransformResult.continueAsIs());

        Submission submission = service.submit(null, "你好", InputTransformRequest.Source.SERVER,
                SubmissionPolicy.serverChat());

        assertEquals(Submission.Kind.REJECTED, submission.getKind());
        assertEquals(Submission.RejectReason.NO_SESSION, submission.getRejectReason());
        assertNull(submission.getSessionId());
        verify(sessions, never()).createDefault();
    }

    @Test
    void submit_should_create_session_when_policy_allows_and_use_the_new_id() {
        Session created = org.mockito.Mockito.mock(Session.class);
        when(created.getSessionId()).thenReturn("new-1");
        when(inputTransforms.transform(null, "你好", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(sessions.createDefault()).thenReturn(created);
        when(inputDirectives.submit(eq("new-1"), eq("你好"), any())).thenReturn(Optional.empty());
        when(harness.chat(eq("new-1"), anyString(), eq("你好"), any())).thenReturn(turn);

        Submission submission = service.submit(null, "你好", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
        assertEquals("new-1", submission.getSessionId());
        verify(sessions).switchTo("new-1");
    }

    @Test
    void submit_should_check_command_presence_with_session_flag_reflecting_input() {
        // 首页（无会话）时命令判定必须拿到 false，否则「首页手敲 /compact 当对话」这条约定会失效
        when(commands.shouldRunAsCommand("/compact", false)).thenReturn(false);
        when(inputTransforms.transform(null, "/compact", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        Session created = org.mockito.Mockito.mock(Session.class);
        when(created.getSessionId()).thenReturn("new-1");
        when(sessions.createDefault()).thenReturn(created);
        when(inputDirectives.submit(eq("new-1"), eq("/compact"), any())).thenReturn(Optional.empty());
        when(harness.chat(eq("new-1"), anyString(), eq("/compact"), any())).thenReturn(turn);

        Submission submission = service.submit(null, "/compact", InputTransformRequest.Source.TUI,
                SubmissionPolicy.tui());

        assertEquals(Submission.Kind.STARTED_TURN, submission.getKind());
        verify(commands).shouldRunAsCommand("/compact", false);
    }

    @Test
    void submit_should_propagate_exception_from_turn_start() {
        when(inputTransforms.transform("s1", "你好", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(harness.chat(eq("s1"), anyString(), eq("你好"), any()))
                .thenThrow(new JellyfishException("执行器已关闭"));

        assertThrows(JellyfishException.class, () -> service.submit("s1", "你好",
                InputTransformRequest.Source.TUI, SubmissionPolicy.tui()));
    }

    @Test
    void submit_should_release_turn_slot_on_terminal_event() {
        java.util.concurrent.atomic.AtomicReference<ReActListener> captured =
                new java.util.concurrent.atomic.AtomicReference<ReActListener>();
        when(inputTransforms.transform("s1", "你好", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(inputDirectives.submit(eq("s1"), eq("你好"), any())).thenReturn(Optional.empty());
        when(harness.chat(eq("s1"), anyString(), eq("你好"), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(3));
            return turn;
        });

        service.submit("s1", "你好", InputTransformRequest.Source.TUI, SubmissionPolicy.tui());

        // 终态之前：占着槽位，第二个提交拿不到
        assertThrows(TurnInProgressException.class, () -> turnRegistry.acquire("s1"));

        // 终态回调之后：槽位自动归还
        captured.get().onComplete(zcd.jellyfish.core.ReActResult.completed("s1", "好", 1));
        TurnRegistry.Slot probe = turnRegistry.acquire("s1");
        turnRegistry.release("s1", probe);
    }

    @Test
    void submit_should_reject_second_turn_on_same_session() {
        givenTurnStarts("s1", "你好");
        service.submit("s1", "你好", InputTransformRequest.Source.TUI, SubmissionPolicy.tui());

        // 第二次走到占位就得被拒：指令解析前的几步要能走通
        when(inputTransforms.transform("s1", "再来一次", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(inputDirectives.submit(eq("s1"), eq("再来一次"), any())).thenReturn(Optional.empty());

        assertThrows(TurnInProgressException.class, () -> service.submit("s1", "再来一次",
                InputTransformRequest.Source.TUI, SubmissionPolicy.tui()));
    }

    @Test
    void submit_should_release_slot_when_turn_start_fails() {
        when(inputTransforms.transform("s1", "你好", InputTransformRequest.Source.TUI))
                .thenReturn(InputTransformResult.continueAsIs());
        when(inputDirectives.submit(eq("s1"), eq("你好"), any())).thenReturn(Optional.empty());
        when(harness.chat(eq("s1"), anyString(), eq("你好"), any()))
                .thenThrow(new JellyfishException("执行器已关闭"));

        assertThrows(JellyfishException.class, () -> service.submit("s1", "你好",
                InputTransformRequest.Source.TUI, SubmissionPolicy.tui()));

        // 槽位没泄漏
        TurnRegistry.Slot probe = turnRegistry.acquire("s1");
        turnRegistry.release("s1", probe);
    }

    @Test
    void policies_should_match_their_declared_shapes() {
        assertEquals(SubmissionPolicy.of(true, true,
                SubmissionPolicy.SessionPolicy.CREATE_IF_NEEDED), SubmissionPolicy.tui());
        assertEquals(SubmissionPolicy.of(true, false,
                SubmissionPolicy.SessionPolicy.REQUIRE_EXISTING), SubmissionPolicy.cli());
        assertEquals(SubmissionPolicy.of(false, false,
                SubmissionPolicy.SessionPolicy.REQUIRE_EXISTING), SubmissionPolicy.serverChat());
    }

    @Test
    void of_should_reject_null_session_policy() {
        assertThrows(JellyfishException.class,
                () -> SubmissionPolicy.of(true, true, null));
    }
}
