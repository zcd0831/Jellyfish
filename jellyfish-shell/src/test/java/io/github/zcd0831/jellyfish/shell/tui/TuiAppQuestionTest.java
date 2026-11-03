package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批提问的整链：外壳渲染编号 → 用户按编号 → 回答是所选选项文本。
 * 规则出处：08-application §8.5（端口契约）。
 */
class TuiAppQuestionTest {

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final AdjustPolicyUseCase adjustPolicy = mock(AdjustPolicyUseCase.class);
    private final SwitchModelUseCase switchModel = mock(SwitchModelUseCase.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);
    private final ShellCancellationToken token = new ShellCancellationToken();
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    private final String[] answered = new String[1];
    private final TuiApp[] holder = new TuiApp[1];

    @Test
    void 按编号选中的是选项文本而不是数字() throws Exception {
        givenSession();
        askDuringRun(Arrays.asList("同意", "不同意"));

        // 输入：发起一轮 → 运行中提问 → 按 1 → 退出
        app("你好\n1\nexit\n").run();

        assertEquals("同意", answered[0], "回答必须是选项文本，Approvals 才判得对");
        assertTrue(screen().contains("1) 同意"), screen());
        assertTrue(screen().contains("3) 其他（自己输入）"), screen());
    }

    @Test
    void 编号越界不当作回答_重新提示后才接受() throws Exception {
        givenSession();
        askDuringRun(Arrays.asList("同意", "不同意"));

        app("你好\n9\n2\nexit\n").run();

        assertEquals("不同意", answered[0]);
        assertTrue(screen().contains("编号超出范围"), "越界必须重新提示，不能默认任何一项：" + screen());
    }

    @Test
    void 选其他后下一行文本成为答案() throws Exception {
        givenSession();
        askDuringRun(Arrays.asList("同意", "不同意"));

        app("你好\n3\n我自己说明\nexit\n").run();

        assertEquals("我自己说明", answered[0]);
    }

    /** 轮次在跑的时候提问：这正是审批与预算确认发生的时机。 */
    private void askDuringRun(final List<String> options) {
        when(runSession.run(eq(SessionId.of("s1")), eq((String) null), any(), any())).thenAnswer(
                new org.mockito.stubbing.Answer<Result<RunSessionResult>>() {
                    @Override
                    public Result<RunSessionResult> answer(InvocationOnMock invocation) {
                        Answer reply = holder[0].ask(new Question(Question.Source.APPROVAL, "同意吗？", options));
                        answered[0] = reply.text();
                        return Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED));
                    }
                });
    }

    private void givenSession() {
        when(startSession.start(any(), any(), eq("你好"))).thenReturn(Result.ok(SessionId.of("s1")));
        when(manageSession.list()).thenReturn(Result.<List<SessionSummary>>ok(
                Collections.<SessionSummary>emptyList()));
    }

    private TuiApp app(String script) throws Exception {
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        Terminal terminal = TerminalBuilder.builder().dumb(true)
                .streams(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream())
                .build();
        TuiApp app = new TuiApp(startSession, runSession, manageSession, adjustPolicy, switchModel, catalog,
                new AnsiRenderer(out, 100, false), new QuestionBroker(), new OutputGateway(out), terminal,
                token, plugins, new TurnStats());
        holder[0] = app;
        return app;
    }

    private String screen() {
        try {
            return screen.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
