package io.github.zcd0831.jellyfish.application.planning;

/** 一轮运行的出口。规则出处：模块-规划 §5、§8.1。 */
public enum RunOutcome {
    CONVERGED,
    SUSPENDED,
    ROUNDS_EXHAUSTED
}
