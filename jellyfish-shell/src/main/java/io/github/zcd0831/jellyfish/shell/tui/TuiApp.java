package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TUI 主循环：输入线程只负责"读一行并分发"，主线程按顺序驱动用例。
 * 单栏流式（D12.1）、JLine 只做输入（D20）、Ctrl-C 两级语义（D20-5）。
 */
public final class TuiApp {

    private static final long INTERRUPT_WINDOW_MILLIS = 2000L;

    private final StartSessionUseCase startSession;
    private final RunSessionUseCase runSession;
    private final ManageSessionUseCase manageSession;
    private final AdjustPolicyUseCase adjustPolicy;
    private final CatalogRepository catalog;
    private final AnsiRenderer renderer;
    private final QuestionBroker broker;
    private final Runnable cancelInFlight;

    private final BlockingQueue<String> commands = new LinkedBlockingQueue<String>();
    private final AtomicInteger interruptions = new AtomicInteger();
    private SessionId currentSession;

    public TuiApp(StartSessionUseCase startSession, RunSessionUseCase runSession,
                  ManageSessionUseCase manageSession, AdjustPolicyUseCase adjustPolicy,
                  CatalogRepository catalog, AnsiRenderer renderer, QuestionBroker broker,
                  Runnable cancelInFlight) {
        this.startSession = startSession;
        this.runSession = runSession;
        this.manageSession = manageSession;
        this.adjustPolicy = adjustPolicy;
        this.catalog = catalog;
        this.renderer = renderer;
        this.broker = broker;
        this.cancelInFlight = cancelInFlight;
    }

    public void run() {
        renderer.info("Jellyfish 1.0.0-alpha　/help 查看命令，exit 退出");
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new CommandCompleter())
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();
            terminal.handle(Terminal.Signal.INT, signal -> onInterrupt());
            startInputThread(reader);
            loop();
        } catch (IOException e) {
            renderer.error("无法初始化终端：" + e.getMessage());
        }
    }

    /** 输入线程：有待答问题就交给提问队列，否则进命令队列。 */
    private void startInputThread(final LineReader reader) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    String line;
                    try {
                        line = reader.readLine(prompt());
                    } catch (UserInterruptException interrupt) {
                        onInterrupt();
                        continue;
                    } catch (EndOfFileException eof) {
                        commands.offer("exit");
                        return;
                    }
                    if (line == null) {
                        commands.offer("exit");
                        return;
                    }
                    if (!broker.deliver(line)) {
                        commands.offer(line);
                    }
                }
            }
        }, "jellyfish-input");
        thread.setDaemon(true);
        thread.start();
    }

    private String prompt() {
        return currentSession == null ? "新会话> " : "继续> ";
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
            if ("exit".equals(text) || "quit".equals(text) || "/exit".equals(text)) {
                renderer.endTurn();
                return;
            }
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("/")) {
                command(text);
                continue;
            }
            handle(text);
        }
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
        ChunkSink sink = new ChunkSink() {
            private boolean started;

            @Override
            public void accept(String delta) {
                if (!started) {
                    renderer.assistantPrefix();
                    started = true;
                }
                renderer.append(delta);
            }
        };
        Result<RunSessionResult> result = runSession.run(currentSession, text, sink);
        renderer.status("");
        renderer.endTurn();
        if (!result.isOk()) {
            printError(result.error());
            return;
        }
        if (result.value().outcome() == RunSessionResult.Outcome.CANCELLED) {
            renderer.info("（已取消，可用 /cancel 确认或继续输入）");
        }
    }

    private void command(String line) {
        String[] parts = line.split("\\s+");
        String name = parts[0];
        if ("/help".equals(name)) {
            renderer.info("/cancel /archive /delete /policy k=v /tools /agents /models /providers /skills /exit");
            return;
        }
        if (currentSession == null && !"/policy".equals(name)) {
            renderer.info("（还没有会话）");
            return;
        }
        if ("/archive".equals(name)) {
            print(manageSession.archive(currentSession));
            currentSession = null;
        } else if ("/delete".equals(name)) {
            print(manageSession.delete(currentSession));
            currentSession = null;
        } else if ("/cancel".equals(name)) {
            broker.cancelPending();
            print(manageSession.cancel(currentSession));
            renderer.info("（已取消，可继续输入或 exit）");
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
        } else {
            renderer.info("未知命令：" + what + "（/help 查看）");
        }
    }

    /** 第一次 Ctrl-C 取消当前轮；两秒内第二次退出（D20-5）。 */
    private void onInterrupt() {
        boolean pendingQuestion = broker.hasPending();
        broker.cancelPending();
        interruptions.incrementAndGet();
        if (interruptions.get() >= 2) {
            renderer.info("（再按一次已退出）".replace("再按一次已退出", "已退出"));
            commands.offer("exit");
            return;
        }
        cancelInFlight.run();
        renderer.info(pendingQuestion ? "（已取消提问；Ctrl-C 再按一次退出）" : "（已请求取消；Ctrl-C 再按一次退出）");
        scheduleInterruptReset();
    }

    private void scheduleInterruptReset() {
        Thread reset = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TimeUnit.MILLISECONDS.sleep(INTERRUPT_WINDOW_MILLIS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                interruptions.set(0);
            }
        }, "jellyfish-interrupt-reset");
        reset.setDaemon(true);
        reset.start();
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

    /** 供测试与补全复用：当前是否有会话（不暴露内部 id）。 */
    public boolean hasSession() {
        return currentSession != null;
    }

    /** 让外壳在退出前主动结束输入线程（JLine 读取是不可中断的阻塞读）。 */
    public void requestExit() {
        commands.offer("exit");
    }

    static Answer cancelledAnswer() {
        return Answer.cancelled();
    }
}
