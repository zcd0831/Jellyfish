package io.github.zcd0831.jellyfish.application.planning;

/**
 * 一轮运行的结果。**取消不是错误**，所以它是正常值的一支。
 * 规则出处：08-application §8.9.1。
 */
public final class RunSessionResult {

    public enum Outcome {
        CONVERGED,
        SUSPENDED,
        ROUNDS_EXHAUSTED,
        CANCELLED
    }

    private final Outcome outcome;

    private RunSessionResult(Outcome outcome) {
        this.outcome = outcome;
    }

    public static RunSessionResult of(Outcome outcome) {
        return new RunSessionResult(outcome);
    }

    public Outcome outcome() {
        return outcome;
    }
}
