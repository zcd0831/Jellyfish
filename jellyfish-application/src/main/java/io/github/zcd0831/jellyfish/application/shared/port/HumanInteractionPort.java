package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.Question;

/**
 * 阻塞式提问：单队列、串行呈现、可被取消打断。规则出处：模块-应用层 §6、技术决策 D05。
 */
public interface HumanInteractionPort {

    Answer ask(Question question);
}
