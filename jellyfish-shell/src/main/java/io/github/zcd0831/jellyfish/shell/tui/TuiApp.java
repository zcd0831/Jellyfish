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
    /** 只有主线程要过提示符（或要提问）时，输入线程才去读行：运行中没人读行，Esc 由监听器接。 */
    private boolean promptWanted;
    private volatile boolean listening;
    private Thread keyListener;
    /** 多行草稿：只在输入线程里读写。 */
    private final DraftInput draft = new DraftInput();
    private CompletableFuture<Answer> pending;
    private volatile SessionId currentSession;
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
                        while (!promptWanted) {
                            waitOnLock();
                        }
                        promptWanted = false;
                        prompt = mode == InputMode.QUESTION ? "回答（Esc 终止）> " : prompt();
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
            if (mode == InputMode.QUESTION && pending != null) {
                if (isExit(text)) {
                    // 正在等答案时退出：先取消本轮，别让主循环卡在提问上
                    pending.complete(Answer.cancelled());
                    mode = InputMode.PAUSED;
                    token.cancel();
                    commands.offer("exit");
                    return;
                }
                pending.complete(Answer.answered(line));
                mode = InputMode.PAUSED;
                return;
            }
        }
        if (draft.accept(line)) {
            // 行尾 `\` 续行：保持 PROMPT，输入线程继续读下一行
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
                command(text);
            } else {
                handle(text);
            }
            resumePrompt();
        }
    }

    private void resumePrompt() {
        synchronized (inputLock) {
            mode = InputMode.PROMPT;
            promptWanted = true;
            inputLock.notifyAll();
        }
    }

    private void pauseInput() {
        synchronized (inputLock) {
            mode = InputMode.PAUSED;
            promptWanted = false;
        }
    }

    private static boolean isExit(String text) {
        return "exit".equals(text) || "quit".equals(text) || "/exit".equals(text);
    }

    private String prompt() {
        if (draft.isContinuing()) {
            return "...> ";
        }
        return currentSession == null ? "新会话> " : "继续> ";
    }


    private void handle(String text) {
        if (currentSession == null) {
            Result<SessionId> started = startSession.start(null, null, text);
            if (!started.isOk()) {
                printError(started.error());
                return;
            }
            currentSession = started.value();
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
            result = runSession.run(currentSession, text, sink, token);
        } finally {
            stopKeyListener();
            restore(original);
        }
        renderer.endTurn();
        if (!result.isOk()) {
            printError(result.error());
            printSummary();
            return;
        }
        if (result.value().outcome() == RunSessionResult.Outcome.CANCELLED) {
            // 已流出的字留在屏幕上，但它们没有进历史（断流不落）——必须说清楚
            renderer.info(sink.rendered()
                    ? "（已取消：上面的回答没有写进历史）"
                    : "（已取消：还没开始回答）");
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
        CompletableFuture<Answer> future = new CompletableFuture<Answer>();
        synchronized (inputLock) {
            pending = future;
            renderer.question(sourceLabel(question.source()), question.prompt());
            mode = InputMode.QUESTION;
            promptWanted = true;
            inputLock.notifyAll();
        }
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Answer.cancelled();
        } catch (java.util.concurrent.ExecutionException broken) {
            return Answer.cancelled();
        } finally {
            synchronized (inputLock) {
                pending = null;
                mode = InputMode.PAUSED;
            }
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

    /** 终止时唤醒阻塞中的提问（Esc 与 exit 共用）。 */
    private void cancelPendingQuestion() {
        synchronized (inputLock) {
            if (pending != null) {
                pending.complete(Answer.cancelled());
            }
        }
    }

    /** Ctrl-C 在提示符处：只清掉这段多行输入，不终止、不退出（Esc 终止，exit/Ctrl-D 退出）。 */
    private void onPromptInterrupt() {
        if (draft.isContinuing()) {
            draft.clear();
        }
        hintTerminate();
    }

    private void command(String line) {
        String[] parts = line.split("\\s+");
        String name = parts[0];
        boolean sessionFree = !Commands.needsSession(name);
        if ("/help".equals(name)) {
            renderer.info("/sessions /open <id> /new /archive /delete /policy k=v /model <id> "
                    + "/tools /agents /models /providers /skills /plugins /exit");
            return;
        }
        if (currentSession == null && !sessionFree) {
            renderer.info("（还没有会话）");
            return;
        }
        if ("/new".equals(name)) {
            // 当前会话留在 sessions/ 里，下一条输入开新会话：不必退出重进
            currentSession = null;
            stats.reset();
            renderer.info("（新会话：下一条输入将开启）");
        } else if ("/sessions".equals(name)) {
            listSessions();
        } else if ("/open".equals(name) && parts.length > 1) {
            openSession(SessionId.of(parts[1]));
        } else if ("/archive".equals(name)) {
            print(manageSession.archive(currentSession));
            currentSession = null;
            renderer.info("（已归档：应用不再列出该会话）");
        } else if ("/delete".equals(name)) {
            print(manageSession.delete(currentSession));
            currentSession = null;
        } else if ("/model".equals(name) && parts.length > 1) {
            print(switchModel.switchTo(currentSession,
                    io.github.zcd0831.jellyfish.domain.shared.ModelId.of(parts[1])));
            renderer.info("（当前模型：" + parts[1] + "）");
        } else if ("/policy".equals(name)) {
            Map<String, Object> changes = new LinkedHashMap<String, Object>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) {
                    changes.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
                }
            }
            print(adjustPolicy.adjust(changes));
            renderer.info("策略已更新（立即生效）");
        } else {
            list(name);
        }
    }

    /** 切到已有会话并回放最近记录：用户想接着聊，不该自己再翻一遍历史。 */
    private void openSession(SessionId sessionId) {
        Result<io.github.zcd0831.jellyfish.application.perception.SessionTranscript> loaded =
                manageSession.transcript(sessionId, REPLAY_RECORDS);
        if (!loaded.isOk()) {
            printError(loaded.error());
            return;
        }
        currentSession = sessionId;
        stats.reset();
        replay(loaded.value());
        stats.restoreContextLabel(loaded.value().contextLabel());
        stats.restoreUsage(loaded.value().consumedTokens(), loaded.value().budgetLimitTokens());
    }

    /** 只回放"人话"：用户与助手正文；工具压一行、摘要一行、系统注入与 reasoning 一律不显示。 */
    private void replay(io.github.zcd0831.jellyfish.application.perception.SessionTranscript transcript) {
        renderer.divider("—— 会话：" + transcript.title() + "（" + transcript.modelId().getValue()
                + "，" + dateFormat().format(new java.util.Date(transcript.createdDate())) + "） ——");
        for (io.github.zcd0831.jellyfish.domain.perception.session.Record record : transcript.records()) {
            switch (record.role()) {
                case USER:
                    renderer.userPrefix();
                    renderer.append(record.content());
                    renderer.endTurn();
                    break;
                case ASSISTANT:
                    renderer.assistantPrefix();
                    renderer.append(record.content());
                    renderer.endTurn();
                    break;
                case TOOL_REQUEST:
                    renderer.info("↳ " + record.content());
                    break;
                case TOOL_RESULT:
                    renderer.info("✓ " + firstLine(record.content(), 80));
                    break;
                case SUMMARY:
                    renderer.info("〔更早对话已压缩为摘要〕");
                    break;
                default:
                    // SYSTEM（经验/SOP 注入）不是对话内容，回放时不显示
            }
        }
        if (transcript.hasEarlier()) {
            renderer.info("（上面是最近 " + REPLAY_RECORDS + " 条，更早的没有显示）");
        }
        StringBuilder line = new StringBuilder("—— 上下文 "
                + transcript.contextLabel().replaceAll("\\s*/\\s*", "/"));
        if (transcript.consumedTokens() > 0) {
            line.append(" / 累计 ").append(transcript.consumedTokens());
            if (transcript.budgetLimitTokens() > 0) {
                line.append(" / 上限 ").append(transcript.budgetLimitTokens());
            }
        }
        renderer.divider(line.append(" ——").toString());
        renderer.info("（已切到该会话，继续输入即可）");
    }

    private static java.text.SimpleDateFormat dateFormat() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
    }

    private static String firstLine(String text, int limit) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > limit ? line.substring(0, limit) + "…" : line;
    }

    /** 会话列表只来自存在的目录。 */
    private void listSessions() {
        Result<java.util.List<io.github.zcd0831.jellyfish.application.perception.port.SessionSummary>> listed =
                manageSession.list();
        if (!listed.isOk()) {
            printError(listed.error());
            return;
        }
        for (io.github.zcd0831.jellyfish.application.perception.port.SessionSummary summary : listed.value()) {
            String marker = summary.isSubSession() ? "  └ " : "";
            renderer.info(marker + summary.id().getValue() + "  " + summary.title()
                    + "  " + summary.modelId().getValue());
        }
    }

    /** 目录只读浏览；增删改走"编辑文件 + 重启"。 */
    private void list(String what) {
        if ("/tools".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition tool : catalog.tools()) {
                renderer.info("tool " + tool.id().getValue() + " [" + tool.status() + "] "
                        + tool.source().getType());
            }
        } else if ("/agents".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition agent : catalog.agents()) {
                renderer.info("agent " + agent.id().getValue() + " [" + agent.status() + "] " + agent.source());
            }
        } else if ("/models".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.model.Model model : catalog.models()) {
                renderer.info("model " + model.id().getValue() + " [" + model.status() + "] " + model.modelName());
            }
        } else if ("/providers".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.model.Provider provider : catalog.providers()) {
                renderer.info("provider " + provider.id().getValue() + " [" + provider.status() + "] "
                        + provider.protocol());
            }
        } else if ("/skills".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition skill : catalog.skills()) {
                renderer.info("skill " + skill.id().getValue() + " [" + skill.status() + "] " + skill.name());
            }
        } else if ("/plugins".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest manifest
                    : plugins.list()) {
                renderer.info("plugin " + manifest.id().getValue() + " v" + manifest.version()
                        + (manifest.isTrusted() ? " [trusted]" : " [untrusted]")
                        + " granted=" + manifest.grantedCapabilities()
                        + " tools=" + manifest.toolIds().size());
            }
        } else {
            renderer.info("未知命令：" + what + "（/help 查看）");
        }
    }

    private void print(Result<?> result) {
        if (!result.isOk()) {
            printError(result.error());
        }
    }

    /** 文案在 shell；缺 key 回退显示 code。 */
    private void printError(UseCaseError error) {
        renderer.error(Messages.text(error.error().code()));
    }

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
