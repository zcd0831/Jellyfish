package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.model.ModelError;

/**
 * 端口抛出的模型侧失败；错误码经 DomainError 表达，文案由外壳翻译（D11）。
 * 规则出处：技术决策 D10（错误分流）、D11（边界 Result + 内部异常）。
 */
public final class ModelInvocationException extends RuntimeException {

    private final ModelError error;

    public ModelInvocationException(ModelError error, String detail) {
        super(detail);
        this.error = error;
    }

    public ModelInvocationException(ModelError error, String detail, Throwable cause) {
        super(detail, cause);
        this.error = error;
    }

    public ModelError error() {
        return error;
    }
}
