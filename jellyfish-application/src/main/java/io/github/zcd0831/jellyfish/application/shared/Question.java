package io.github.zcd0831.jellyfish.application.shared;

import java.util.List;

/**
 * 阻塞式提问。必须标注来源，否则用户不知道在跟谁说话。
 * 规则出处：模块-应用层 §6（HumanInteractionPort）。
 */
public final class Question {

    public enum Source {
        MAIN_SESSION, SUBAGENT, VERIFICATION, APPROVAL, BUDGET
    }

    private final Source source;
    private final String prompt;
    private final List<String> options;

    public Question(Source source, String prompt, List<String> options) {
        this.source = source;
        this.prompt = prompt;
        this.options = options;
    }

    public Source source() {
        return source;
    }

    public String prompt() {
        return prompt;
    }

    public List<String> options() {
        return options;
    }
}
