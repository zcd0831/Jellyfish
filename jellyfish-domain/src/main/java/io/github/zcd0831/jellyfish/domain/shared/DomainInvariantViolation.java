package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 不变量被违反——是程序 bug，不是业务分支。
 * 规则出处：技术决策 D11（必须冒到最外层，记 error 日志 + 现场快照，不得包成 Result）。
 */
public final class DomainInvariantViolation extends RuntimeException {

    public DomainInvariantViolation(String message) {
        super(message);
    }
}
