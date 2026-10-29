package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外壳冒烟：脚本输入 → 起会话 → 跑一轮 → exit 退出，全程不碰真终端。
 * 规则出处：08-application §8.9.4.2（外壳只调用例）、§8.9.4.1（Esc/exit 的入口划分）。
 */
class TuiAppSmokeTest {

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final AdjustPolicyUseCase adjustPolicy = mock(AdjustPolicyUseCase.class);
    private final SwitchModelUseCase switchModel = mock(SwitchModelUseCase.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @Test
    void 输入一句话跑一轮后exit退出() throws Exception {
        when(startSession.start(any(), any(), eq("你好"))).thenReturn(Result.ok(SessionId.of("s1")));
        when(runSession.run(eq(SessionId.of("s1")), eq((String) null), any(), any()))
                .thenReturn(Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED)));
        when(manageSession.list()).thenReturn(okList());

        app("你好\n/help\nexit\n").run();

        verify(startSession, timeout(3000)).start(any(), any(), eq("你好"));
        verify(runSession, timeout(3000)).run(eq(SessionId.of("s1")), eq((String) null), any(), any());
        assertTrue(screen().contains("/sessions"), "命令路径也要能走通：" + screen());
    }

    @Test
    void 直接exit不开启任何会话() throws Exception {
        when(manageSession.list()).thenReturn(okList());

        app("exit\n").run();

        verify(startSession, org.mockito.Mockito.never()).start(any(), any(), any());
        assertFalse(screen().contains("新会话>"), "exit 之后不该再出提示符：" + screen());
    }

    private TuiApp app(String script) throws Exception {
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        Terminal terminal = TerminalBuilder.builder().dumb(true)
                .streams(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream())
                .build();
        return new TuiApp(startSession, runSession, manageSession, adjustPolicy, switchModel, catalog,
                new AnsiRenderer(out, 100, false), new QuestionBroker(), new OutputGateway(out), terminal,
                new ShellCancellationToken(), plugins, new TurnStats());
    }

    private static Result<List<SessionSummary>> okList() {
        return Result.ok(Collections.<SessionSummary>emptyList());
    }

    private String screen() {
        try {
            return screen.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
