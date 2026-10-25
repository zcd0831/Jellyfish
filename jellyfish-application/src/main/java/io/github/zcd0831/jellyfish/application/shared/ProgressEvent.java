package io.github.zcd0831.jellyfish.application.shared;

/**
 * 面向界面的进度：可丢弃，不是领域事实。
 * <p>
 * `message` 自带进度信息（"第 3/5 步：…"、"read_file 成功 12ms"、"+1.2k / 累计 3.1k"），
 * 外壳只按 `Kind` 决定排版——应用层不关心怎么显示。
 * 规则出处：模块-应用层 §6（ProgressPort）。
 */
public final class ProgressEvent {

    public enum Kind {
        SUMMARIZING, SUMMARY_DONE, STEP_STARTED, STEP_FINISHED,
        SUBAGENTS_RUNNING, MODEL_SWITCHED, RETRY_SCHEDULED,
        PLAN_CREATED, TOOL_STARTED, TOOL_FINISHED, USAGE, CONTEXT_USAGE
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
