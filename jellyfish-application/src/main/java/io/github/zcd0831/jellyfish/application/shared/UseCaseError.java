package io.github.zcd0831.jellyfish.application.shared;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/**
 * 用例失败的统一出口：上下文自己的错误 + 只进日志的细节。
 * 规则出处：技术决策 D11。
 */
public final class UseCaseError {

    private final DomainError error;
    private final String detail;
    private final Object context;

    private UseCaseError(DomainError error, String detail, Object context) {
        this.error = error;
        this.detail = detail;
        this.context = context;
    }

    public static UseCaseError of(DomainError error, String detail, Object context) {
        return new UseCaseError(error, detail, context);
    }

    public DomainError error() {
        return error;
    }

    public String detail() {
        return detail;
    }

    public Object context() {
        return context;
    }
}
