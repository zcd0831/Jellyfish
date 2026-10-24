package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 本期唯一的shell：读一行、渲染流式输出、把 cancel 交给端口。
 * 规则出处：技术决策 D12.1（本期只做 TUI）、D06（流式只喂界面）。
 */
public final class TuiShell {

    private final StartSessionUseCase startSession;
    private final RunSessionUseCase runSession;
    private final ManageSessionUseCase manageSession;
    private final AdjustPolicyUseCase adjustPolicy;
    private final BufferedReader in;
    private final PrintStream out;

    private SessionId currentSession;

    public TuiShell(StartSessionUseCase startSession, RunSessionUseCase runSession,
                    ManageSessionUseCase manageSession, AdjustPolicyUseCase adjustPolicy,
                    InputStream in, PrintStream out) {
        this.startSession = startSession;
        this.runSession = runSession;
        this.manageSession = manageSession;
        this.adjustPolicy = adjustPolicy;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public void run() {
        out.println("Jellyfish 1.0.0-alpha（S1：无计划对话）");
        out.println("直接输入问题；exit 退出");
        while (true) {
            out.print(currentSession == null ? "新会话> " : "继续> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                out.println("读取输入失败：" + e.getMessage());
                return;
            }
            if (line == null || "exit".equals(line.trim()) || "quit".equals(line.trim())) {
                return;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line.trim().startsWith("/")) {
                command(line.trim());
                continue;
            }
            handle(line);
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
            // 会话开启已写入首条 USER 记录，本轮只跑模型
            runOnce(null);
            return;
        }
        runOnce(text);
    }

    /** 管理命令：/archive /delete /cancel /policy k=v [k=v ...]。 */
    private void command(String line) {
        String[] parts = line.split("\\s+");
        String name = parts[0];
        if (currentSession == null) {
            out.println("（还没有会话）");
            return;
        }
        if ("/archive".equals(name)) {
            print(manageSession.archive(currentSession));
            currentSession = null;
        } else if ("/delete".equals(name)) {
            print(manageSession.delete(currentSession));
            currentSession = null;
        } else if ("/cancel".equals(name)) {
            print(manageSession.cancel(currentSession));
            out.println("（已取消，可继续输入或 exit）");
        } else if ("/policy".equals(name)) {
            java.util.Map<String, Object> changes = new java.util.LinkedHashMap<String, Object>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) {
                    changes.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
                }
            }
            print(adjustPolicy.adjust(changes));
            out.println("策略已更新（立即生效）");
        } else {
            out.println("未知命令：" + name + "（可用 /archive /delete /cancel /policy）");
        }
    }

    private void print(Result<?> result) {
        if (!result.isOk()) {
            printError(result.error());
        }
    }

    private void runOnce(String text) {
        ChunkSink sink = new ChunkSink() {
            @Override
            public void accept(String delta) {
                out.print(delta);
                out.flush();
            }
        };
        Result<RunSessionResult> result = runSession.run(currentSession, text, sink);
        out.println();
        if (!result.isOk()) {
            printError(result.error());
            return;
        }
        if (result.value().outcome() == RunSessionResult.Outcome.CANCELLED) {
            out.println("（已取消）");
        }
    }

    /** 文案在 shell；缺 key 时回退显示 code（D11）。 */
    private void printError(UseCaseError error) {
        out.println("错误：" + Messages.text(error.error().code()));
    }
}
