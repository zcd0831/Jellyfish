package io.github.zcd0831.jellyfish.domain.action.plugin;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/** 插件上下文错误码。。 */
public enum PluginError implements DomainError {

    INVALID_ID("PL_INVALID_ID"),
    INCOMPATIBLE("PL_INCOMPATIBLE"),
    DEPENDENCY_CONFLICT("PL_DEPENDENCY_CONFLICT"),
    PORT_CONFLICT("PL_PORT_CONFLICT"),
    NOT_FOUND("PL_NOT_FOUND"),
    UNDECLARED("PL_UNDECLARED");

    private final String code;

    PluginError(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
