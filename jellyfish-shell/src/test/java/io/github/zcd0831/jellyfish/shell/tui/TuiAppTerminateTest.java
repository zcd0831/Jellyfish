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
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行中的 Esc：轮到模型/工具在跑时没人读行，终止由按键监听器接（08-application §8.9.4.1）。
 * 外加一条：`Esc` 后面紧跟的字节（Alt+Enter / 方向键）不能被误判成终止。
 */
class TuiAppTerminateTest {

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final AdjustPolicyUseCase adjustPolicy = mock(AdjustPolicyUseCase.class);
    private final SwitchModelUseCase switchModel = mock(SwitchModelUseCase.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);
    private final ShellCancellationToken token = new ShellCancellationToken();
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @Test
    void 运行中按Esc终止本轮() throws Exception {
        givenSession();
        blockedUntilTerminated(3000);

        // 单按 Esc（下一个字节来自另一次按键，隔了 500ms）
        appWithTypedAhead(new long[]{0L, 300L, 500L}, new String[]{"你好\n", "\u001B", "exit\n"}).run();

        verify(runSession, timeout(5000)).run(eq(SessionId.of("s1")), eq((String) null), any(), any());
        assertTrue(token.isCancelled(), "运行中按 Esc 必须真的把令牌置上");
        assertTrue(screen().contains("已取消"), "界面要明确说这轮被终止了：" + screen());
    }

    @Test
    void Esc后面紧跟字节时不算终止() throws Exception {
        givenSession();
        // 慢一点结束：给监听器时间把 Esc + CR（Alt+Enter）当作转义序列吃掉
        when(runSession.run(eq(SessionId.of("s1")), eq((String) null), any(), any())).thenAnswer(
                new Answer<Result<RunSessionResult>>() {
                    @Override
                    public Result<RunSessionResult> answer(InvocationOnMock invocation) throws Throwable {
                        Thread.sleep(400);
                        return Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED));
                    }
                });

        // Esc + CR 是一次 Alt+Enter：两个字节粘在一起
        appWithTypedAhead(new long[]{0L, 200L, 400L}, new String[]{"你好\n", "\u001B\r", "exit\n"}).run();

        assertFalse(token.isCancelled(), "Esc 后面还有字节 = Alt/转义序列，不是终止");
        assertFalse(screen().contains("已取消"), screen());
    }

    private void givenSession() {
        when(startSession.start(any(), any(), eq("你好"))).thenReturn(Result.ok(SessionId.of("s1")));
        when(manageSession.list()).thenReturn(Result.<List<SessionSummary>>ok(
                Collections.<SessionSummary>emptyList()));
    }

    /** 轮次阻塞到"令牌被置上"为止：这正是真实模型调用/工具执行时的样子。 */
    private void blockedUntilTerminated(final long maxMillis) {
        when(runSession.run(eq(SessionId.of("s1")), eq((String) null), any(), any())).thenAnswer(
                new Answer<Result<RunSessionResult>>() {
                    @Override
                    public Result<RunSessionResult> answer(InvocationOnMock invocation) throws Throwable {
                        long until = System.currentTimeMillis() + maxMillis;
                        while (!token.isCancelled() && System.currentTimeMillis() < until) {
                            Thread.sleep(10);
                        }
                        return Result.ok(RunSessionResult.of(token.isCancelled()
                                ? RunSessionResult.Outcome.CANCELLED : RunSessionResult.Outcome.CONVERGED));
                    }
                });
    }

    private TuiApp app(String script) throws Exception {
        return app(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 按键要在「轮次已经在跑」之后才到达：JLine 会把抢跑的字节收进自己的 pushback，监听器看不到；
     * 真实用户也是等模型开始跑之后才按 Esc 的。<br>
     * chunks 是「延迟毫秒 → 送出的字节」序列，最后一段送完即关闭输入（EOF）。
     */
    private TuiApp appWithTypedAhead(long[] delays, String[] chunks) throws Exception {
        final java.io.PipedOutputStream toApp = new java.io.PipedOutputStream();
        java.io.PipedInputStream fromTest = new java.io.PipedInputStream(toApp);
        toApp.write(chunks[0].getBytes(StandardCharsets.UTF_8));
        toApp.flush();
        Thread feeder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 1; i < chunks.length; i++) {
                        Thread.sleep(delays[i]);
                        toApp.write(chunks[i].getBytes(StandardCharsets.UTF_8));
                        toApp.flush();
                    }
                    toApp.close();
                } catch (Exception ignored) {
                    // 测试结束就结束
                }
            }
        }, "feeder");
        feeder.setDaemon(true);
        feeder.start();
        return app(fromTest);
    }

    private TuiApp app(java.io.InputStream in) throws Exception {
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        Terminal terminal = TerminalBuilder.builder().dumb(true)
                .streams(in, new ByteArrayOutputStream())
                .build();
        return new TuiApp(startSession, runSession, manageSession, adjustPolicy, switchModel, catalog,
                new AnsiRenderer(out, 100, false), new QuestionBroker(), new OutputGateway(out), terminal,
                token, plugins, new TurnStats());
    }

    private String screen() {
        try {
            return screen.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
