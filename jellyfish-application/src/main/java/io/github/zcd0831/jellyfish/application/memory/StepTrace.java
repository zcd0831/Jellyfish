package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.planning.plan.RecordRange;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个 step 的执行事实摘要，带轨迹区间供内置归纳代理读原始对话。规则出处：模块-记忆 §5。 */
public final class StepTrace {

    private final StepId stepId;
    private final String description;
    private final StepStatus outcome;
    private final RecordRange recordRange;
    private final List<SkillId> usedSkillIds;
    private final String failureReason;
    private final String reflectionConclusion;

    public StepTrace(StepId stepId, String description, StepStatus outcome, RecordRange recordRange,
                     List<SkillId> usedSkillIds,
                     String failureReason, String reflectionConclusion) {
        this.stepId = stepId;
        this.description = description;
        this.outcome = outcome;
        this.recordRange = recordRange;
        this.usedSkillIds = Collections.unmodifiableList(new ArrayList<SkillId>(
                usedSkillIds == null ? Collections.<SkillId>emptyList() : usedSkillIds));
        this.failureReason = failureReason;
        this.reflectionConclusion = reflectionConclusion;
    }

    public StepId stepId() {
        return stepId;
    }

    public String description() {
        return description;
    }

    public StepStatus outcome() {
        return outcome;
    }

    public RecordRange recordRange() {
        return recordRange;
    }

    public List<SkillId> usedSkillIds() {
        return usedSkillIds;
    }

    public String failureReason() {
        return failureReason;
    }

    public String reflectionConclusion() {
        return reflectionConclusion;
    }
}
