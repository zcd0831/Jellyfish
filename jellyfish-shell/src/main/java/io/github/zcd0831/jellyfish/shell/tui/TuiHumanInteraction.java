package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;

/**
 * 阻塞式提问：呈现与队列都由 shell 负责，终端只归外壳（D19）。
 * 单队列、串行呈现、标注来源（D05-2、D20-6）。
 */
public final class TuiHumanInteraction implements HumanInteractionPort {

    private final QuestionBroker broker;

    public TuiHumanInteraction(QuestionBroker broker) {
        this.broker = broker;
    }

    @Override
    public Answer ask(Question question) {
        return broker.ask(question);
    }

    /** Ctrl-C：唤醒阻塞中的提问，让主循环尽快收尾。 */
    public void cancel() {
        broker.cancelPending();
    }
}
