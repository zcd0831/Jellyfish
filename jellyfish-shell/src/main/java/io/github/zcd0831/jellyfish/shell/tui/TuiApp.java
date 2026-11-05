package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TUI 主循环：输入线程独占 stdin，主循环按顺序驱动用例。
 * <p>
 * 输入线程有三种状态，切换由主循环控制：**等待提示符** / **轮次进行中（不读）** / **等一个答案**。
 * 轮次进行中不读输入，是为了让流式输出独占终端——否则每个增量都要经 `printAbove`
 * 把提示符重画一遍。
 */
public final class TuiApp implements QuestionBroker.Answerer {

    /** 回放条数：约 30 轮对话，再多也没人看。 */
    private static final int REPLAY_RECORDS = 100;
    private enum InputMode {
        PROMPT, PAUSED, QUESTION
    }

    private final StartSessionUseCase startSession;
    private final RunSessionUseCase runSession;
    private final ManageSessionUseCase manageSession;
    private final AdjustPolicyUseCase adjustPolicy;
    private final SwitchModelUseCase switchModel;
    private final CatalogRepository catalog;
    private final ManagePluginsUseCase plugins;
    private final AnsiRenderer renderer;
    private final OutputGateway gateway;
    private final Terminal terminal;
    private final TurnStats stats;
    private final ShellCancellationToken token;

    private final BlockingQueue<String> commands = new LinkedBlockingQueue<String>();
    private final Object inputLock = new Object();

    private InputMode mode = InputMode.PAUSED;
    private TerminateWatcher watcher;
    private final EchoControl echo;
    /** 多行草稿：只在输入线程里读写。 */
    private final DraftInput draft = new DraftInput();
    /** 提问单队列 + 答案解释：所有来源排队逐个呈现（08-application §8.11）。 */
    private final QuestionConsole questions;
    private final SessionCursor cursor = new SessionCursor();
    private final CommandDispatcher dispatcher;
    private LineReader reader;

    public TuiApp(StartSessionUseCase startSession, RunSessionUseCase runSession,
                  ManageSessionUseCase manageSession, AdjustPolicyUseCase adjustPolicy,
                  SwitchModelUseCase switchModel, CatalogRepository catalog, AnsiRenderer renderer,
                  QuestionBroker broker, OutputGateway gateway, Terminal terminal, ShellCancellationToken token,
                  ManagePluginsUseCase plugins, TurnStats stats) {
        this.startSession = startSession;
        this.runSession = runSession;
        this.manageSession = manageSession;
        this.adjustPolicy = adjustPolicy;
        this.switchModel = switchModel;
        this.catalog = catalog;
        this.plugins = plugins;
        this.renderer = renderer;
        this.gateway = gateway;
        this.terminal = terminal;
        this.token = token;
        this.stats = stats;
        this.echo = new EchoControl(terminal);
        broker.bind(this);
        this.questions = new QuestionConsole(renderer, inputLock, new QuestionConsole.ModeSink() {
            @Override
            public void onQuestion() {
                mode = InputMode.QUESTION;
                inputLock.notifyAll();
            }

            @Override
            public void onPaused() {
                mode = InputMode.PAUSED;
            }
        });
        this.dispatcher = new CommandDispatcher(renderer, stats, manageSession, adjustPolicy, switchModel,
                plugins, catalog, cursor);
    }

    public void run() {
        renderer.divider("Jellyfish 1.0.0-alpha　/help 查看命令，Esc 终止本轮，exit 退出");
        // 监听器与终端类型无关：非 TTY 下轮次进行中也要能按 Esc（测试走的就是 dumb 终端）
        watcher = new TerminateWatcher(terminal, new TerminateWatcher.TerminateSink() {
            @Override
            public void onTerminate() {
                TuiApp.this.onTerminate();   // 不写外部类限定会递归调到自己
            }
        });
        if (Terminal.TYPE_DUMB.equals(terminal.getType())) {
            renderer.info("（当前不是终端：无行编辑、无颜色；输入按行读取）");
            startDumbInput(System.in);
        } else {
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CommandCompleter(new TuiParameterCandidates(manageSession, catalog)))
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();
            terminal.handle(Terminal.Signal.INT, signal -> hintTerminate());
            watcher.bindKeys(reader);
            startInputThread();
        }
        resumePrompt();
        loop();
    }

    /** 提问：整体委托给 {@link QuestionConsole}（单队列 + 答案解释都在那边）。 */
    @Override
    public Answer ask(Question question) {
        return questions.ask(question);
    }

    /** Esc：终止本轮（在途模型调用与工具随之收手；子代理级联终止）。 */
    void onTerminate() {
        synchronized (inputLock) {
            questions.cancelPending();   // 先把排队的提问叫醒，别让主循环卡在上面
        }
        token.cancel();
        renderer.info("（已终止本轮：可继续输入，exit 退出）");
    }

    /** Ctrl-C 不再承担终止/退出：只提示替代按键，避免用户以为程序卡住。 */
    private void hintTerminate() {
        renderer.info("（Ctrl-C 不再用于终止：按 Esc 终止本轮，exit 或 Ctrl-D 退出）");
    }

    private void startInputThread() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    String prompt;
                    synchronized (inputLock) {
                        while (mode == InputMode.PAUSED) {
                            waitOnLock();
                        }
                        prompt = mode == InputMode.QUESTION ? questions.promptText() : prompt();
                    }
                    String line;
                    gateway.reading(reader);
                    try {
                        line = reader.readLine(prompt);
                    } catch (UserInterruptException interrupt) {
                        gateway.idle();
                        onPromptInterrupt();
                        continue;
                    } catch (EndOfFileException eof) {
                        gateway.idle();
                        commands.offer("exit");
                        return;
                    }
                    gateway.idle();
                    if (line == null) {
                        commands.offer("exit");
                        return;
                    }
                    deliver(line);
                }
            }
        }, "jellyfish-input");
        thread.setDaemon(true);
        thread.start();
    }

    /** 非 TTY：没有行编辑，输出侧是否着色由外壳判定。 */
    private void startDumbInput(final InputStream input) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader source = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = source.readLine()) != null) {
                        deliver(line);
                    }
                } catch (IOException eof) {
                    // 输入结束等同 exit
                }
                commands.offer("exit");
            }
        }, "jellyfish-input");
        thread.setDaemon(true);
        thread.start();
    }

    /** 答案进提问、其余进命令队列：任何时刻只有一个消费者。 */
    private void deliver(String line) {
        String text = line == null ? "" : line.trim();
        synchronized (inputLock) {
            if (mode == InputMode.QUESTION && questions.hasPending()) {
                if (isExit(text)) {
                    // 正在等答案时退出：先终止本轮，别让主循环卡在提问上
                    questions.cancelAll();
                    mode = InputMode.PAUSED;
                    token.cancel();
                    commands.offer("exit");
                    return;
                }
                questions.offer(text);   // 已交给提问（无效回答由它重新提示）
                return;
            }
        }
        if (draft.accept(line)) {
            // 行尾 `\` 续行：mode 仍是 PROMPT，输入线程继续读下一行
            return;
        }
        String complete = draft.take();
        synchronized (inputLock) {
            mode = InputMode.PAUSED;
        }
        commands.offer(complete);
    }

    private void loop() {
        while (true) {
            String line;
            try {
                line = commands.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            String text = line.trim();
            if (isExit(text)) {
                renderer.endTurn();
                gateway.close();
                return;
            }
            if (text.isEmpty()) {
                resumePrompt();
                continue;
            }
            if (text.startsWith("/")) {
                dispatcher.command(text);
            } else {
                handle(text);
            }
            resumePrompt();
        }
    }

    /**
     * 回到提示符。**有人正在等答案时不抢输入**：提问可能在主循环空闲时进来（并行子代理的
     * ask_user），把 mode 无条件改回 PROMPT 会让那一行输入被当成普通输入交给用例。
     * 规则出处：08-application §8.11（单队列，cancellation 可唤醒）。
     */
    private void resumePrompt() {
        synchronized (inputLock) {
            if (mode == InputMode.QUESTION) {
                return;
            }
            mode = InputMode.PROMPT;
            inputLock.notifyAll();
        }
    }

    private void pauseInput() {
        synchronized (inputLock) {
            mode = InputMode.PAUSED;
        }
    }

    private static boolean isExit(String text) {
        return "exit".equals(text) || "quit".equals(text) || "/exit".equals(text);
    }

    private String prompt() {
        if (draft.isContinuing()) {
            return "...> ";
        }
        return cursor.current() == null ? "新会话> " : "继续> ";
    }


    private void handle(String text) {
        if (cursor.current() == null) {
            Result<SessionId> started = startSession.start(null, null, text);
            if (!started.isOk()) {
                dispatcher.printError(started.error());
                return;
            }
            cursor.moveTo(started.value());
            runOnce(null);
            return;
        }
        runOnce(text);
    }

    private void runOnce(String text) {
        pauseInput();
        token.reset();
        stats.reset();
        renderer.status("思考中…");
        org.jline.terminal.Attributes original = echo.off();
        watcher.start();
        TurnSink sink = new TurnSink(renderer);
        Result<RunSessionResult> result;
        try {
            result = runSession.run(cursor.current(), text, sink, token);
        } finally {
            watcher.stop();
            echo.back(original);
        }
        renderer.endTurn();
        if (!result.isOk()) {
            dispatcher.printError(result.error());
            printSummary();
            return;
        }
        if (result.value().outcome() == RunSessionResult.Outcome.CANCELLED) {
            // 已流出的字留在屏幕上，但它们没有进历史（断流不落）——必须说清楚
            renderer.info(sink.rendered()
                    ? "（已终止：上面的回答没有写进历史）"
                    : "（已终止：还没开始回答）");
        }
        printSummary();
    }

    /** 回合小结：一行 dim 文本，回看时知道这一轮花了多少、做了几步。 */
    private void printSummary() {
        String summary = stats.summary();
        if (summary != null) {
            renderer.divider(summary);
        }
    }

    /** Ctrl-C 在提示符处：只清掉这段多行输入，不终止、不退出（Esc 终止，exit/Ctrl-D 退出）。 */
    private void onPromptInterrupt() {
        if (draft.isContinuing()) {
            draft.clear();
        }
        hintTerminate();
    }


    /**
     * 终止一个会话（当前会话或按 id 指定的子代理会话）：在途模型调用与工具收手、子会话级联。
     * 本轮在跑时输入被暂停，那种情况下用 Esc；这条命令的价值是**按 id 终止别的会话**。
     * 规则出处：08-application §8.9.4（终止）、§8.9.4.1。
     */

    /** 切到已有会话并回放最近记录：用户想接着聊，不该自己再翻一遍历史。 */

    /** 只回放"人话"：用户与助手正文；工具压一行、摘要一行、系统注入与 reasoning 一律不显示。 */



    /** 会话列表只来自存在的目录。 */

    /** 目录只读浏览；增删改走"编辑文件 + 重启"。 */


    /** 文案在 shell；缺 key 回退显示 code。 */

    private void waitOnLock() {
        try {
            inputLock.wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
