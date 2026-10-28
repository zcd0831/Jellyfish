package io.github.zcd0831.jellyfish.application.planning;

/** 一轮运行的出口。规则出处：06-planning §6.6、§6.9.1。 */
public enum RunOutcome {
    CONVERGED,
    SUSPENDED,
    ROUNDS_EXHAUSTED
}
