package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;

import java.util.concurrent.CompletableFuture;

/**
 * 提问的单队列：输入线程把下一行交给"当前待答问题"，其余行归命令。
 * 主循环会被 ask 阻塞，而输入线程仍在读——这是 D05-1 的落地方式。
 */
public final class QuestionBroker {

    private final AnsiRenderer renderer;
    private CompletableFuture<Answer> pending;

    public QuestionBroker(AnsiRenderer renderer) {
        this.renderer = renderer;
    }

    /** 阻塞式提问：呈现问题后等输入线程把答案送进来。 */
    public Answer ask(Question question) {
        CompletableFuture<Answer> future = new CompletableFuture<Answer>();
        synchronized (this) {
            pending = future;
        }
        renderer.question(sourceLabel(question.source()), question.prompt());
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Answer.cancelled();
        } catch (java.util.concurrent.ExecutionException broken) {
            return Answer.cancelled();
        } finally {
            synchronized (this) {
                pending = null;
            }
        }
    }

    /** 输入线程调用：有待答问题就消费这一行，返回 true。 */
    public boolean deliver(String line) {
        CompletableFuture<Answer> target;
        synchronized (this) {
            target = pending;
        }
        if (target == null) {
            return false;
        }
        target.complete(Answer.answered(line));
        return true;
    }

    /** 取消时唤醒阻塞中的 ask（用户 Ctrl-C 或会话被取消）。 */
    public void cancelPending() {
        CompletableFuture<Answer> target;
        synchronized (this) {
            target = pending;
        }
        if (target != null) {
            renderer.status("");
            target.complete(Answer.cancelled());
        }
    }

    public boolean hasPending() {
        synchronized (this) {
            return pending != null;
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
