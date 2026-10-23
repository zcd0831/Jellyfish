package io.github.zcd0831.jellyfish.application.shared;

/**
 * 面向界面的进度：可丢弃，不是领域事实。
 * 规则出处：模块-应用层 §6（ProgressPort）、技术决策 D14（日志不重复领域事实）。
 */
public final class ProgressEvent {

    public enum Kind {
        SUMMARIZING, SUMMARY_DONE, STEP_STARTED, STEP_FINISHED,
        SUBAGENTS_RUNNING, MODEL_SWITCHED, RETRY_SCHEDULED
    }

    private final Kind kind;
    private final String message;

    public ProgressEvent(Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    public static ProgressEvent of(Kind kind, String message) {
        return new ProgressEvent(kind, message);
    }

    public Kind kind() {
        return kind;
    }

    public String message() {
        return message;
    }
}
