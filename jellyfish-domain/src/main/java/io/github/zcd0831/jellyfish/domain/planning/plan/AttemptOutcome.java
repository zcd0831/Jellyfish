package io.github.zcd0831.jellyfish.domain.planning.plan;

/**
 * 一次尝试的结局只有两种：做了（成功/失败）。“没做完”不结束尝试，续跑接着做。
 * 规则出处：06-planning §6.8.1、08-application §8.9.4.1。
 */
public enum AttemptOutcome {
    SUCCEEDED,
    FAILED
}
