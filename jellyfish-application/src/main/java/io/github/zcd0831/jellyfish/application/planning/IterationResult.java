package io.github.zcd0831.jellyfish.application.planning;

/**
 * 内循环一次迭代的输出。规则出处：模块-规划 §5（IterationResult）。
 */
public final class IterationResult {

    private final boolean isConverged;
    private final boolean submitted;
    private final String submittedSummary;
    private final int attemptNo;

    public IterationResult(boolean isConverged, boolean submitted, String submittedSummary, int attemptNo) {
        this.isConverged = isConverged;
        this.submitted = submitted;
        this.submittedSummary = submittedSummary;
        this.attemptNo = attemptNo;
    }

    public static IterationResult converged(int attemptNo) {
        return new IterationResult(true, false, null, attemptNo);
    }

    public static IterationResult submitted(String summary, int attemptNo) {
        return new IterationResult(true, true, summary, attemptNo);
    }

    public static IterationResult notConverged(int attemptNo) {
        return new IterationResult(false, false, null, attemptNo);
    }

    public boolean isConverged() {
        return isConverged;
    }

    public boolean submitted() {
        return submitted;
    }

    public String submittedSummary() {
        return submittedSummary;
    }

    public int attemptNo() {
        return attemptNo;
    }
}
