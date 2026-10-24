package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 沉淀入参：由外循环组装，记忆**不遍历 Plan 聚合**；只给事实摘要与轨迹区间。
 * 规则出处：模块-记忆 §5、§8.2、M17。
 */
public final class DistillationInput {

    private final PlanId planId;
    private final SessionId sessionId;
    private final String goal;
    private final PlanStatus outcome;
    private final List<StepTrace> stepTraces;

    public DistillationInput(PlanId planId, SessionId sessionId, String goal, PlanStatus outcome,
                             List<StepTrace> stepTraces) {
        this.planId = planId;
        this.sessionId = sessionId;
        this.goal = goal;
        this.outcome = outcome;
        this.stepTraces = Collections.unmodifiableList(new ArrayList<StepTrace>(
                stepTraces == null ? Collections.<StepTrace>emptyList() : stepTraces));
    }

    public PlanId planId() {
        return planId;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public String goal() {
        return goal;
    }

    public PlanStatus outcome() {
        return outcome;
    }

    public List<StepTrace> stepTraces() {
        return stepTraces;
    }
}
