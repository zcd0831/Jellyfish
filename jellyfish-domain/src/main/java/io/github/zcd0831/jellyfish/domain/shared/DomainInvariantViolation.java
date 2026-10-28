package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 不变量被违反——是程序 bug，不是业务分支。
 */
public final class DomainInvariantViolation extends RuntimeException {

    public DomainInvariantViolation(String message) {
        super(message);
    }
}
