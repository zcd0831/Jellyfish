package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索入参：**不传 Plan 聚合**，只给 planId 与各步摘要。
 * 规则出处：07-memory §7.6、§7.9.1。
 */
public final class RetrievalInput {

    private final PlanId planId;
    private final List<StepBrief> steps;

    public RetrievalInput(PlanId planId, List<StepBrief> steps) {
        this.planId = planId;
        this.steps = Collections.unmodifiableList(new ArrayList<StepBrief>(
                steps == null ? Collections.<StepBrief>emptyList() : steps));
    }

    public PlanId planId() {
        return planId;
    }

    public List<StepBrief> steps() {
        return steps;
    }
}
