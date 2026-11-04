package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提问单队列：主循环验收与并行子代理 ask_user 同时提问时，排队逐个呈现。
 * 规则出处：08-application §8.11（HumanInteractionPort 单队列，所有来源排队逐个呈现）。
 */
class TuiAppAskQueueTest {

    private static final long WAIT_MILLIS = 5000L;

    private final StartSessionUseCase startSession = mock(StartSessionUseCase.class);
    private final RunSessionUseCase runSession = mock(RunSessionUseCase.class);
    private final ManageSessionUseCase manageSession = mock(ManageSessionUseCase.class);
    private final AdjustPolicyUseCase adjustPolicy = mock(AdjustPolicyUseCase.class);
    private final SwitchModelUseCase switchModel = mock(SwitchModelUseCase.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final ManagePluginsUseCase plugins = mock(ManagePluginsUseCase.class);
    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();

    @Test
    void 两个来源同时提问要排队逐个呈现() throws Exception {
        when(startSession.start(any(), any(), eq("你好"))).thenReturn(Result.ok(
                io.github.zcd0831.jellyfish.domain.shared.SessionId.of("s1")));
        when(manageSession.list()).thenReturn(Result.<List<SessionSummary>>ok(
                Collections.<SessionSummary>emptyList()));

        FeedStream input = new FeedStream();
        final TuiApp app = app(input);
        Thread tui = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    app.run();
                } catch (Throwable crashed) {
                    crashed.printStackTrace(System.out);
                }
            }
        }, "tui-test");
        tui.setDaemon(true);
        tui.start();

        final Map<String, String> answers = Collections.synchronizedMap(new LinkedHashMap<String, String>());
        Thread first = asker(app, answers, "问题一");
        first.start();
        awaitScreen("问题一");
        Thread second = asker(app, answers, "问题二");
        second.start();

        input.feed("1\n");
        awaitScreen("问题二");            // 第一问答完，第二问才呈现
        input.feed("2\n");
        first.join(WAIT_MILLIS);
        second.join(WAIT_MILLIS);
        input.feed("exit\n");
        tui.join(WAIT_MILLIS);

        assertEquals("同意", answers.get("问题一"), "先到的提问方必须拿到自己的答案");
        assertEquals("不同意", answers.get("问题二"), "后到的提问方不能用先到的答案");
        assertEquals(2, answers.size());
    }

    private Thread asker(final TuiApp app, final Map<String, String> answers, final String prompt) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Answer answer = app.ask(new Question(Question.Source.APPROVAL, prompt,
                        Arrays.asList("同意", "不同意")));
                answers.put(prompt, answer.text());
            }
        }, "asker-" + prompt);
        thread.setDaemon(true);
        return thread;
    }

    /** 按需喂行的输入流：JLine 在 Piped 上读不到数据，自己给一个可阻塞的流。 */
    private static final class FeedStream extends java.io.InputStream {

        private final java.util.concurrent.BlockingQueue<Integer> bytes =
                new java.util.concurrent.LinkedBlockingQueue<Integer>();

        void feed(String text) {
            for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                bytes.add((int) b);
            }
        }

        @Override
        public int read() {
            try {
                return bytes.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            buffer[offset] = (byte) first;
            int count = 1;
            while (count < length && !bytes.isEmpty()) {
                buffer[offset + count] = (byte) (int) bytes.poll();
                count++;
            }
            return count;
        }

        @Override
        public int available() {
            return bytes.size();
        }
    }

    private void awaitScreen(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (screen().contains(expected)) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("等不到「" + expected + "」，屏幕上是：" + screen());
    }

    private TuiApp app(java.io.InputStream input) throws Exception {
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        Terminal terminal = TerminalBuilder.builder().dumb(true)
                .streams(input, new ByteArrayOutputStream())
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
