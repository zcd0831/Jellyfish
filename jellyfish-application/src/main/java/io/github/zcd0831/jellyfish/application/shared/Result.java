package io.github.zcd0831.jellyfish.application.shared;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

import java.util.function.Function;

/**
 * 用例的返回：预期内的失败是值，不是异常。
 */
public final class Result<T> {

    private final T value;
    private final UseCaseError error;

    private Result(T value, UseCaseError error) {
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> fail(UseCaseError error) {
        return new Result<>(null, error);
    }

    public static <T> Result<T> fail(DomainError error) {
        return new Result<>(null, UseCaseError.of(error, null, null));
    }

    public boolean isOk() {
        return error == null;
    }

    public T value() {
        if (!isOk()) {
            throw new IllegalStateException("failed result: " + error.error().code());
        }
        return value;
    }

    public UseCaseError error() {
        if (isOk()) {
            throw new IllegalStateException("ok result has no error");
        }
        return error;
    }

    public <R> Result<R> map(Function<? super T, ? extends R> mapper) {
        return isOk() ? Result.ok(mapper.apply(value)) : Result.fail(error);
    }
}
