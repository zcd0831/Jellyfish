package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;

/**
 * 提问端口的适配：把阻塞提问转给外壳主循环（同一个 LineReader 读答案）。
 * 用"装配时绑定"打断构造环：用例需要端口，外壳需要用例（D20-8）。
 */
public final class QuestionBroker {

    /** 由外壳实现：暂停当前输出、就地读一行答案。 */
    public interface Answerer {
        Answer ask(Question question);
    }

    private volatile Answerer answerer;

    public void bind(Answerer answerer) {
        this.answerer = answerer;
    }

    public Answer ask(Question question) {
        Answerer target = answerer;
        return target == null ? Answer.cancelled() : target.ask(question);
    }
}
