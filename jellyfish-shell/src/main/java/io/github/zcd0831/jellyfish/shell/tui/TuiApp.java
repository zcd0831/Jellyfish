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
    /** 运行中监听按键的轮询步长：只影响"多久发现一次按键"，不影响终止延迟。 */
    private static final long LISTEN_POLL_MILLIS = 100L;
    /** `Esc` 后等后续字节的窗口：超时即认定「单按 Esc」。 */
    private static final long ESC_WINDOW_MILLIS = 120L;
    /** 转义序列最多还有这么多个字节（方向键 2 个、Alt+Enter 1 个，留足余量）。 */
    private static final int ESC_SEQUENCE_MAX_BYTES = 8;
    private static final int ESC = 0x1B;
    /** JLine 非阻塞读取的 EOF（-2 是"等超时"，不算 EOF）。 */
    private static final int EOF = -1;

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
    private volatile boolean listening;
    private Thread keyListener;
    /** 多行草稿：只在输入线程里读写。 */
    private final DraftInput draft = new DraftInput();
    /** 提问单队列：所有来源排队逐个呈现，受 inputLock 保护（08-application §8.11）。 */
    private final java.util.ArrayDeque<Pending> questions = new java.util.ArrayDeque<Pending>();
    /** 队首问题：deliver 解析编号与「其他（自己输入）」时要用到。 */
    private Question pendingQuestion;
    /** 已选「其他（自己输入）」：下一行非空文本即答案。 */
    private boolean awaitingFreeText;
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
        broker.bind(this);
        this.dispatcher = new CommandDispatcher(renderer, stats, manageSession, adjustPolicy, switchModel,
                plugins, catalog, cursor);
    }

    public void run() {
        renderer.divider("Jellyfish 1.0.0-alpha　/help 查看命令，Esc 终止本轮，exit 退出");
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
            bindKeys();
            startInputThread();
        }
        resumePrompt();
        loop();
    }

    /** `Esc` 终止、`Alt+Enter` 换行；绑定失败不影响行尾 `\` 续行。 */
    private void bindKeys() {
        try {
            TerminateKey.bind(reader, new Runnable() {
                @Override
                public void run() {
                    onTerminate();
                }
            });
        } catch (RuntimeException unsupported) {
            // 终端/键位表不支持就算了，行尾 `\` 仍可用
        }
    }

    /** Esc：终止本轮（在途模型调用与工具随之收手；子代理级联终止）。 */
    void onTerminate() {
        cancelPendingQuestion();
        token.cancel();
        renderer.info("（已终止本轮：可继续输入，exit 退出）");
    }

    /**
     * 运行中的按键监听：这段时间没人读行，Esc 只能靠这里接（08-application §8.9.4.1）。
     * <p>判别自己做：`Esc` 后 {@link #ESC_WINDOW_MILLIS}ms 内还有字节，就当作 Alt/转义序列、不终止；
     * 等到超时才是「单按一次 Esc」。这正是 JLine 键位表做不到的事。
     */
    private void startKeyListener() {
        listening = true;
        keyListener = new Thread(new Runnable() {
            @Override
            public void run() {
                while (listening) {
                    if (!listenOnce()) {
                        return;
                    }
                }
            }
        }, "jellyfish-terminate-key");
        keyListener.setDaemon(true);
        keyListener.start();
    }

    private void stopKeyListener() {
        listening = false;
        Thread listener = keyListener;
        keyListener = null;
        if (listener != null) {
            try {
                listener.join(LISTEN_POLL_MILLIS * 4L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 返回 false 表示终端已关闭，监听结束。
     * <p>用 peek 而不是 read：<strong>不是 Esc 的按键一个都不消费</strong>，
     * 于是运行中打好的字仍然留在终端缓冲里，下一个提示符照常收到（type-ahead 不丢）。
     */
    private boolean listenOnce() {
        try {
            int peeked = terminal.reader().peek(LISTEN_POLL_MILLIS);
            if (peeked == EOF) {
                return false;
            }
            if (peeked != ESC) {
                return true;   // 不是 Esc：留给下一个提示符
            }
            terminal.reader().read();   // 消费掉这个 Esc，再做窗口判别
            int next = terminal.reader().peek(ESC_WINDOW_MILLIS);
            if (next == EOF) {
                return false;
            }
            if (next < 0) {
                onTerminate();          // 窗口内没有后续字节：这就是「单按一次 Esc」
                return true;
            }
            drainEscapeSequence();      // Alt+X / 方向键：整串吃掉，别让它污染下一次输入
            return true;
        } catch (IOException closed) {
            return false;
        }
    }

    /** 转义序列的尾巴（`[A`、`CR` 之类）可能分几批到：持续吞到静默为止，最多一小段。 */
    private void drainEscapeSequence() {
        for (int i = 0; i < ESC_SEQUENCE_MAX_BYTES; i++) {
            try {
                int more = terminal.reader().read(ESC_WINDOW_MILLIS);
                if (more < 0) {
                    return;
                }
            } catch (IOException closed) {
                return;
            }
        }
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
                        prompt = mode == InputMode.QUESTION ? questionPrompt() : prompt();
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
            Pending current = questions.peekFirst();
            if (mode == InputMode.QUESTION && current != null) {
                if (isExit(text)) {
                    // 正在等答案时退出：先终止本轮，别让主循环卡在提问上
                    cancelAllQuestions();
                    mode = InputMode.PAUSED;
                    token.cancel();
                    commands.offer("exit");
                    return;
                }
                Answer chosen = interpret(text);
                if (chosen == null) {
                    return;   // 无效回答：不完成提问，重新提示（08-application §8.5）
                }
                current.future.complete(chosen);
                awaitingFreeText = false;
                // 后面还有提问就由它的 ask() 收尾时接着呈现，这里先让输入线程停下
                mode = InputMode.PAUSED;
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
        org.jline.terminal.Attributes original = withoutEcho();
        startKeyListener();
        TurnOutput sink = new TurnOutput();
        Result<RunSessionResult> result;
        try {
            result = runSession.run(cursor.current(), text, sink, token);
        } finally {
            stopKeyListener();
            restore(original);
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

    /** 本轮输出：记住有没有打出过字（取消时据此说清"上面那段没保存"）。 */
    private final class TurnOutput implements ChunkSink {
        private boolean started;

        @Override
        public void accept(String delta) {
            if (!started) {
                renderer.status("");
                renderer.assistantPrefix();
                started = true;
            }
            renderer.append(delta);
        }

        boolean rendered() {
            return started;
        }
    }

    /**
     * 提问：在锁内先把问题打出去，再切到"等答案"，输入线程才开始画 `回答>`。
     * 顺序反了就会出现"提示符在前、问题被顶到下一行"。
     */
    @Override
    public Answer ask(Question question) {
        Pending mine = new Pending(question);
        synchronized (inputLock) {
            // 单队列：后来的排在队尾，等前一个答完才呈现（08-application §8.11）
            questions.addLast(mine);
            if (questions.peekFirst() == mine) {
                present(mine);
            }
        }
        try {
            return mine.future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Answer.cancelled();
        } catch (java.util.concurrent.ExecutionException broken) {
            return Answer.cancelled();
        } finally {
            synchronized (inputLock) {
                questions.remove(mine);
                Pending next = questions.peekFirst();
                if (next != null) {
                    present(next);          // 唤醒下一个提问方，别的来源不必永远等
                } else {
                    pendingQuestion = null;
                    awaitingFreeText = false;
                    mode = InputMode.PAUSED;
                }
            }
        }
    }

    /** 呈现队首问题：调用方必须已持 inputLock。 */
    private void present(Pending entry) {
        pendingQuestion = entry.question;
        awaitingFreeText = false;
        renderer.question(sourceLabel(entry.question.source()), entry.question.prompt(),
                entry.question.options());
        mode = InputMode.QUESTION;
        inputLock.notifyAll();
    }

    /** 一次提问的登记项。 */
    private static final class Pending {

        private final Question question;
        private final CompletableFuture<Answer> future = new CompletableFuture<Answer>();

        Pending(Question question) {
            this.question = question;
        }
    }

    /**
     * 轮次进行中关掉回显：终端不在 readLine 里，敲键会被 TTY 直接回显到正文中间。
     * ISIG 不动，Ctrl-C 仍然能到达信号处理器。
     */
    private org.jline.terminal.Attributes withoutEcho() {
        try {
            org.jline.terminal.Attributes original = terminal.getAttributes();
            org.jline.terminal.Attributes copy = new org.jline.terminal.Attributes(original);
            copy.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, false);
            terminal.setAttributes(copy);
            return original;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private void restore(org.jline.terminal.Attributes original) {
        if (original == null) {
            return;
        }
        try {
            terminal.setAttributes(original);
        } catch (RuntimeException ignored) {
            // 恢复失败交给终端自己兜底
        }
    }

    /** 等答案时的提示符：选了「其他」就要自由文本，否则提示可选的编号范围。 */
    private String questionPrompt() {
        Question question = pendingQuestion;
        if (question == null || question.options() == null || question.options().isEmpty()) {
            return "回答（Esc 终止）> ";
        }
        if (awaitingFreeText) {
            return "回答（Esc 终止）> ";
        }
        return "回答（1-" + (question.options().size() + 1) + "，Esc 终止）> ";
    }

    /**
     * 把一行输入解释成回答；返回 null 表示这行不算回答，要重新提示。
     * 规则出处：08-application §8.5（带 options 时按编号选，自动追加「其他（自己输入）」）。
     */
    private Answer interpret(String text) {
        Question question = pendingQuestion;
        ChoiceInput.Result result = ChoiceInput.parse(text,
                question == null ? null : question.options(), awaitingFreeText);
        if (result.wantsFreeText) {
            awaitingFreeText = true;
            renderer.info("请输入你的回答（Esc 终止）");
            return null;
        }
        if (result.reject != null) {
            renderer.invalidAnswer(result.reject);
            return null;
        }
        return Answer.answered(result.answer);
    }

    /** 终止时唤醒阻塞中的提问（Esc 与 exit 共用）。 */
    private void cancelPendingQuestion() {
        synchronized (inputLock) {
            cancelAllQuestions();
            pendingQuestion = null;
            awaitingFreeText = false;
            mode = InputMode.PAUSED;
        }
    }

    /** 把排队的提问全部取消：只取消队首会把后排永久留在 future 上。调用方已持锁。 */
    private void cancelAllQuestions() {
        while (!questions.isEmpty()) {
            questions.pollFirst().future.complete(Answer.cancelled());
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

    private static String sourceLabel(Question.Source source) {
        switch (source) {
            case SUBAGENT:
                return "子代理";
            case VERIFICATION:
                return "验收";
            case APPROVAL:
                return "放行审批";
            case BUDGET:
                return "预算预警";
            default:
                return "会话";
        }
    }
}
