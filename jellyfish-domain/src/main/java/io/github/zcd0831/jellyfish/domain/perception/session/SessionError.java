package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/** 会话上下文错误码。规则出处：技术决策 D11 code 前缀表（S_）。 */
public enum SessionError implements DomainError {

    NOT_FOUND("S_NOT_FOUND"),
    NO_USABLE_MODEL("S_NO_USABLE_MODEL"),
    NO_USABLE_AGENT("S_NO_USABLE_AGENT");

    private final String code;

    SessionError(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
