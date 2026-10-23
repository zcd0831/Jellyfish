package io.github.zcd0831.jellyfish.application.shared;

/**
 * 提问的回答；取消是独立分支，不是错误。
 * 规则出处：技术决策 D11（取消不是错误）。
 */
public final class Answer {

    public enum Kind {
        ANSWERED, CANCELLED
    }

    private final Kind kind;
    private final String text;
    private final String reason;

    private Answer(Kind kind, String text, String reason) {
        this.kind = kind;
        this.text = text;
        this.reason = reason;
    }

    public static Answer answered(String text) {
        return new Answer(Kind.ANSWERED, text, null);
    }

    public static Answer answered(String text, String reason) {
        return new Answer(Kind.ANSWERED, text, reason);
    }

    public static Answer cancelled() {
        return new Answer(Kind.CANCELLED, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    public String reason() {
        return reason;
    }
}
