package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.DomainError;

/** 规划上下文错误码。规则出处：技术决策 D11 code 前缀表（P_）。 */
public enum PlanningError implements DomainError {

    INVALID_PLAN("P_INVALID_PLAN"),
    NO_CURRENT_STEP("P_NO_CURRENT_STEP"),
    STEP_NOT_RUNNING("P_STEP_NOT_RUNNING"),
    REPLAY_EXHAUSTED("P_REPLAY_EXHAUSTED"),
    PLAN_TERMINATED("P_PLAN_TERMINATED");

    private final String code;

    PlanningError(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
