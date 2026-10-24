package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 反馈入参：本次注入的经验集合 + 检索子代理建议的经验集合。
 * 成功只奖励"被建议的"，失败惩罚"注入的全部"（奖惩不对称）。
 * 规则出处：模块-记忆 §5、§7.1 规则 ⑦⑧、§8.3。
 */
public final class FeedbackInput {

    private final StepId stepId;
    private final List<ExperienceId> injectedExperienceIds;
    private final List<ExperienceId> suggestedExperienceIds;

    public FeedbackInput(StepId stepId, List<ExperienceId> injectedExperienceIds,
                         List<ExperienceId> suggestedExperienceIds) {
        this.stepId = stepId;
        this.injectedExperienceIds = immutable(injectedExperienceIds);
        this.suggestedExperienceIds = immutable(suggestedExperienceIds);
    }

    private static List<ExperienceId> immutable(List<ExperienceId> source) {
        return Collections.unmodifiableList(new ArrayList<ExperienceId>(
                source == null ? Collections.<ExperienceId>emptyList() : source));
    }

    public StepId stepId() {
        return stepId;
    }

    public List<ExperienceId> injectedExperienceIds() {
        return injectedExperienceIds;
    }

    public List<ExperienceId> suggestedExperienceIds() {
        return suggestedExperienceIds;
    }
}
