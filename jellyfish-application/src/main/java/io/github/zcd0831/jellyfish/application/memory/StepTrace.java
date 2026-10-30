package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一个 step 的执行事实摘要：不带对话区间，素材由编排者 fork 当前生效窗口带入。规则出处：07-memory §7.6、§8.9.7。 */
public final class StepTrace {

    private final StepId stepId;
    private final String description;
    private final StepStatus outcome;
    private final List<SkillId> usedSkillIds;
    private final String failureReason;
    private final String reflectionConclusion;

    public StepTrace(StepId stepId, String description, StepStatus outcome,
                     List<SkillId> usedSkillIds,
                     String failureReason, String reflectionConclusion) {
        this.stepId = stepId;
        this.description = description;
        this.outcome = outcome;
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
