package io.github.zcd0831.jellyfish.infrastructure.terminal;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 阻塞式提问：单队列（同一把锁串行呈现）、标注来源、可被 cancel 唤醒。
 * 规则出处：模块-基础设施层 §7、技术决策 D05-2。
 */
public final class TerminalHumanInteraction implements HumanInteractionPort {

    private final BufferedReader in;
    private final PrintStream out;
    private final Object turn = new Object();
    private volatile boolean cancelled;

    public TerminalHumanInteraction(InputStream in, PrintStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    @Override
    public Answer ask(Question question) {
        synchronized (turn) {
            if (cancelled) {
                return Answer.cancelled();
            }
            out.println("[" + label(question.source()) + "] " + question.prompt());
            out.print("> ");
            out.flush();
            try {
                String line = in.readLine();
                if (line == null) {
                    return Answer.cancelled();
                }
                return Answer.answered(line);
            } catch (IOException e) {
                return Answer.cancelled();
            }
        }
    }

    /** 由外壳在 Ctrl-C 时调用：唤醒下一次 ask，让主循环尽快收尾。 */
    public void cancel() {
        cancelled = true;
    }

    public void resume() {
        cancelled = false;
    }

    private static String label(Question.Source source) {
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
