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
 * TUI 主循环：输入线程独占 stdin（D05 约束 1），主循环按顺序驱动用例。
 * <p>
 * 输入线程有三种状态，切换由主循环控制：**等待提示符** / **轮次进行中（不读）** / **等一个答案**。
 * 轮次进行中不读输入，是为了让流式输出独占终端——否则每个增量都要经 `printAbove`
 * 把提示符重画一遍（D20-2、D20-8）。
 */
public final class TuiApp implements QuestionBroker.Answerer {

    private static final long DOUBLE_INTERRUPT_MILLIS = 2000L;
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
    private final AtomicInteger interruptions = new AtomicInteger();
    private final AtomicLong lastPromptInterrupt = new AtomicLong();
    private final Object inputLock = new Object();

    private InputMode mode = InputMode.PAUSED;
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
        renderer.divider("Jellyfish 1.0.0-alpha　/help 查看命令，exit 退出");
        if (Terminal.TYPE_DUMB.equals(terminal.getType())) {
            renderer.info("（当前不是终端：无行编辑、无颜色；输入按行读取）");
            startDumbInput(System.in);
        } else {
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CommandCompleter(new TuiParameterCandidates(manageSession, catalog)))
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();
            terminal.handle(Terminal.Signal.INT, signal -> onInterrupt());
            bindInsertNewline();
            startInputThread();
        }
        resumePrompt();
        loop();
    }

    /** `Alt+Enter` 在缓冲区插入换行（C1 乙）；绑定失败不影响行尾 `\` 续行。 */
    private void bindInsertNewline() {
        try {
            reader.getWidgets().put("jellyfish-insert-newline", new InsertNewlineWidget(reader));
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new org.jline.reader.Reference("jellyfish-insert-newline"), "\u001B\r");
        } catch (RuntimeException unsupported) {
            // 终端/键位表不支持就算了，行尾 `\` 仍可用
        }
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
                        prompt = mode == InputMode.QUESTION ? "回答（/cancel 放弃）> " : prompt();
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

    /** 非 TTY：没有行编辑，输出侧是否着色由外壳判定（D20-7）。 */
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
                if ("/cancel".equals(text)) {
                    // 提问里也能放弃：等价于用户拒绝回答，答案按取消回给调用方
                    pending.complete(Answer.cancelled());
                    mode = InputMode.PAUSED;
                    return;
                }
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
        TurnOutput sink = new TurnOutput();
        Result<RunSessionResult> result;
        try {
            result = runSession.run(currentSession, text, sink, token);
        } finally {
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
     * 轮次进行中关掉回显：终端不在 readLine 里，敲键会被 TTY 直接回显到正文中间（D20-9）。
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

    /** 取消时唤醒阻塞中的提问（`/cancel` 与 Ctrl-C 共用）。 */
    private void cancelPendingQuestion() {
        synchronized (inputLock) {
            if (pending != null) {
                pending.complete(Answer.cancelled());
            }
        }
    }

    /** Ctrl-C 在轮次中：取消本轮；连按两次退出（D20-5）。 */
    private void onInterrupt() {
        cancelPendingQuestion();
        if (interruptions.incrementAndGet() >= 2) {
            commands.offer("exit");
            return;
        }
        token.cancel();
    }

    /** Ctrl-C 在提示符处：清行继续，两秒内第二次退出（D20-5）。 */
    private void onPromptInterrupt() {
        if (draft.isContinuing()) {
            draft.clear();
            renderer.info("（已放弃这段多行输入）");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPromptInterrupt.getAndSet(now) <= DOUBLE_INTERRUPT_MILLIS) {
            commands.offer("exit");
            return;
        }
        renderer.info("（已清空当前输入，再按一次 Ctrl-C 退出）");
    }

    private void command(String line) {
        String[] parts = line.split("\\s+");
        String name = parts[0];
        boolean sessionFree = !Commands.needsSession(name);
        if ("/help".equals(name)) {
            renderer.info("/sessions /open <id> /cancel /archive /delete /policy k=v /model <id> "
                    + "/tools /agents /models /providers /skills /plugins /exit");
            return;
        }
        if (currentSession == null && !sessionFree) {
            renderer.info("（还没有会话）");
            return;
        }
        if ("/sessions".equals(name)) {
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
        } else if ("/cancel".equals(name)) {
            token.cancel();
            cancelPendingQuestion();
            print(manageSession.cancel(currentSession));
            renderer.info("（已取消，可继续输入或 exit）");
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

    /** 会话列表只来自存在的目录（D22：没有状态字段，存在即可用）。 */
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

    /** 目录只读浏览；增删改按 D12 走"编辑文件 + 重启"。 */
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

    /** 文案在 shell；缺 key 回退显示 code（D11）。 */
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
