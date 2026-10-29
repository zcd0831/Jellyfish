package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SessionTranscript;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外壳的命令与输入路径：`/sessions`、`/open`、`/new`、多行草稿，以及 `ask_user` 的往返。
 * 规则出处：08-application §8.9.4.2（外壳只调用例）、§8.11（人机交互单队列）。
 */
class TuiAppCommandTest {

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final AdjustPolicyUseCase adjustPolicy = mock(AdjustPolicyUseCase.class);
    private final SwitchModelUseCase switchModel = mock(SwitchModelUseCase.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @Test
    void sessions列出会话_open回放正文() throws Exception {
        when(startSession.start(any(), any(), eq("你好"))).thenReturn(Result.ok(SessionId.of("s1")));
        when(runSession.run(eq(SessionId.of("s1")), eq((String) null), any(), any()))
                .thenReturn(Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED)));
        when(manageSession.list()).thenReturn(Result.ok(Collections.singletonList(
                new SessionSummary(SessionId.of("s1"), null, "旧会话", ModelId.of("m1"), 0L))));
        when(manageSession.transcript(eq(SessionId.of("s1")), anyInt())).thenReturn(Result.ok(transcript()));

        app("/sessions\n/open s1\nexit\n").run();

        assertTrue(screen().contains("旧会话"), "要能列出会话：" + screen());
        assertTrue(screen().contains("你好"), "回放要显示用户正文：" + screen());
        assertTrue(screen().contains("在的"), "回放要显示助手正文：" + screen());
    }

    @Test
    void new之后下一条输入开新会话() throws Exception {
        when(startSession.start(any(), any(), any())).thenReturn(Result.ok(SessionId.of("s1")),
                Result.ok(SessionId.of("s2")));
        when(runSession.run(any(SessionId.class), eq((String) null), any(), any()))
                .thenReturn(Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED)));
        when(manageSession.list()).thenReturn(okEmpty());

        app("你好\n/new\n再一句\nexit\n").run();

        verify(startSession, timeout(3000)).start(any(), any(), eq("你好"));
        verify(startSession, timeout(3000)).start(any(), any(), eq("再一句"));
    }

    @Test
    void 行尾反斜杠续行拼成一条输入() throws Exception {
        when(startSession.start(any(), any(), any())).thenReturn(Result.ok(SessionId.of("s1")));
        when(runSession.run(any(SessionId.class), eq((String) null), any(), any()))
                .thenReturn(Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CONVERGED)));
        when(manageSession.list()).thenReturn(okEmpty());

        app("第一行\\\n第二行\nexit\n").run();

        verify(startSession, timeout(3000)).start(any(), any(), eq("第一行\n第二行"));
    }

    @Test
    void ask往返把答案交回调用方() throws Exception {
        when(manageSession.list()).thenReturn(okEmpty());
        PipedOutputStream toApp = new PipedOutputStream();
        PipedInputStream fromTest = new PipedInputStream(toApp);
        TuiApp app = app(fromTest);
        Thread ui = new Thread(new Runnable() {
            @Override
            public void run() {
                app.run();
            }
        }, "ui");
        ui.setDaemon(true);
        ui.start();

        // 一个写者线程写完全部脚本再关：Piped 流不允许"写者线程死了还开着"
        feedAll(toApp);
        Thread.sleep(200);   // 等外壳把提示符挂上，再让 ask 切到 QUESTION

        Answer answer = app.ask(new Question(Question.Source.VERIFICATION, "同意吗？", null));

        assertEquals("同意", answer.text(), "答案要按原样回给调用方");
        ui.interrupt();          // 提问本身发生在轮次里：这里不跑真轮次，直接收掉外壳线程
        ui.join(2000L);
        assertTrue(!ui.isAlive(), "外壳线程要能被收掉");
    }

    private void feedAll(final PipedOutputStream toApp) {
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(400L);
                    toApp.write("同意\n".getBytes(StandardCharsets.UTF_8));
                    toApp.flush();
                    toApp.close();
                } catch (Exception ignored) {
                    // 测试收尾
                }
            }
        }, "feeder");
        writer.setDaemon(true);
        writer.start();
    }

    private static SessionTranscript transcript() {
        Record user = new Record(RecordId.of("r1"), RecordRole.USER, "你好", null, 1, 1L);
        Record assistant = new Record(RecordId.of("r2"), RecordRole.ASSISTANT, "在的", null, null,
                "m1", null, 0, 2, 2L);
        return new SessionTranscript(SessionId.of("s1"), "旧会话", ModelId.of("m1"), 1L,
                Arrays.asList(user, assistant), false, "1k / 50k", 10L, 1000L);
    }

    private static Result<List<SessionSummary>> okEmpty() {
        return Result.ok(Collections.<SessionSummary>emptyList());
    }

    private TuiApp app(String script) throws Exception {
        return app(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
    }

    private TuiApp app(java.io.InputStream in) throws Exception {
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        Terminal terminal = TerminalBuilder.builder().dumb(true)
                .streams(in, new ByteArrayOutputStream())
                .build();
        return new TuiApp(startSession, runSession, manageSession, adjustPolicy, switchModel, catalog,
                new AnsiRenderer(out, 100, false), new QuestionBroker(), new OutputGateway(out), terminal,
                new ShellCancellationToken(), plugins, new TurnStats());
    }

    private String screen() {
        try {
            return screen.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
