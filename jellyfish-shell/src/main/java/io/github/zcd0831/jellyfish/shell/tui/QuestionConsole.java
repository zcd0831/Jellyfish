package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * 提问单队列 + 答案解释：所有来源（内循环 ask_user、并行子代理、验收、审批、预算）排成一队逐个呈现，
 * 后来的等前一个答完（08-application §8.11）。
 * <p>
 * 队列与输入状态受同一个锁保护，锁由外壳持有并传进来——提问要改的"当前是什么模式"属于外壳，
 * 所以这里只通过 {@link ModeSink} 通知，不直接摸外壳的字段。
 */
final class QuestionConsole {

    /** 对外壳输入模式的通知：进入提问 / 回到暂停。 */
    interface ModeSink {
        void onQuestion();

        void onPaused();
    }

    private final AnsiRenderer renderer;
    private final Object lock;
    private final ModeSink modes;

    private final ArrayDeque<Pending> queue = new ArrayDeque<Pending>();
    private Question current;
    private boolean awaitingFreeText;

    QuestionConsole(AnsiRenderer renderer, Object lock, ModeSink modes) {
        this.renderer = renderer;
        this.lock = lock;
        this.modes = modes;
    }

    /** 有人在等答案吗？调用方须已持锁。 */
    boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * 提问：在锁内先把问题打出去，再切到"等答案"，输入线程才开始画 `回答>`。
     * 顺序反了就会出现"提示符在前、问题被顶到下一行"。
     */
    Answer ask(Question question) {
        Pending mine = new Pending(question);
        synchronized (lock) {
            // 单队列：后来的排在队尾，等前一个答完才呈现
            queue.addLast(mine);
            if (queue.peekFirst() == mine) {
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
            synchronized (lock) {
                queue.remove(mine);
                Pending next = queue.peekFirst();
                if (next != null) {
                    present(next);          // 唤醒下一个提问方，别的来源不必永远等
                } else {
                    current = null;
                    awaitingFreeText = false;
                    modes.onPaused();
                }
            }
        }
    }

    /** 呈现队首问题：调用方必须已持锁。 */
    private void present(Pending entry) {
        current = entry.question;
        awaitingFreeText = false;
        renderer.question(sourceLabel(entry.question.source()), entry.question.prompt(),
                entry.question.options());
        modes.onQuestion();
    }

    /**
     * 等答案期间收到一行：true 表示这一行已被"提问"消费（无论是否算有效回答）。
     * 调用方须已持锁。
     */
    boolean offer(String text) {
        Pending head = queue.peekFirst();
        if (head == null) {
            return false;
        }
        Answer chosen = interpret(text);
        if (chosen == null) {
            return true;   // 无效回答：不完成提问，重新提示（08-application §8.5）
        }
        head.future.complete(chosen);
        awaitingFreeText = false;
        // 后面还有提问就由它的 ask() 收尾时接着呈现，这里先让输入线程停下
        modes.onPaused();
        return true;
    }

    /** 等答案时的提示符：选了「其他」就要自由文本，否则提示可选的编号范围。 */
    String promptText() {
        Question question = current;
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
        Question question = current;
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

    /** 终止时唤醒阻塞中的提问（Esc 与 exit 共用）。调用方须已持锁。 */
    void cancelPending() {
        cancelAll();
        current = null;
        awaitingFreeText = false;
        modes.onPaused();
    }

    /** 把排队的提问全部取消：只取消队首会把后排永久留在 future 上。调用方已持锁。 */
    void cancelAll() {
        while (!queue.isEmpty()) {
            queue.pollFirst().future.complete(Answer.cancelled());
        }
    }

    /** 一次提问的登记项。 */
    private static final class Pending {

        private final Question question;
        private final CompletableFuture<Answer> future = new CompletableFuture<Answer>();

        Pending(Question question) {
            this.question = question;
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
