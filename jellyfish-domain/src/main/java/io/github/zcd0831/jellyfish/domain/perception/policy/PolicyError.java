package io.github.zcd0831.jellyfish.domain.perception.policy;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/** 策略上下文错误码。。 */
public enum PolicyError implements DomainError {

    INVALID_VALUE("C_INVALID_VALUE");

    private final String code;

    PolicyError(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
