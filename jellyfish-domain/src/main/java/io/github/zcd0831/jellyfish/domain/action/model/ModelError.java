package io.github.zcd0831.jellyfish.domain.action.model;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/** 模型上下文错误码。。 */
public enum ModelError implements DomainError {

    UNAVAILABLE("M_UNAVAILABLE"),
    AUTH_FAILED("M_AUTH_FAILED"),
    MODEL_NOT_FOUND("M_MODEL_NOT_FOUND"),
    CONTEXT_EXCEEDED("M_CONTEXT_EXCEEDED");

    private final String code;

    ModelError(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
