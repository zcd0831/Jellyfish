package io.github.zcd0831.jellyfish.domain.planning.plan;

/**
 * 计划状态：只有"执行中"与两个终态。重划不是独立状态——`replan_plan` 一次原子替换
 * 未执行尾部，计划始终在 EXECUTING 里（06-planning §6.6 不变量 ③）。
 */
public enum PlanStatus {
    EXECUTING,
    COMPLETED,
    FAILED
}
