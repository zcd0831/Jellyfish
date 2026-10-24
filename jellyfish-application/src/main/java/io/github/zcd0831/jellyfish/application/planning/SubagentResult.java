package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 子代理的结论：各自独立成败，不整体失败。规则出处：模块-行动 §8.1。
 */
public final class SubagentResult {

    private final SessionId sessionId;
    private final String conclusion;

    public SubagentResult(SessionId sessionId, String conclusion) {
        this.sessionId = sessionId;
        this.conclusion = conclusion;
    }

    public static SubagentResult of(SessionId sessionId, String conclusion) {
        return new SubagentResult(sessionId, conclusion);
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public String conclusion() {
        return conclusion;
    }
}
