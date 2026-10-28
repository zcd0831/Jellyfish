package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 反馈入参：该 step 本次分配到的经验集合。
 * 分配就是注入，注入就是"被应用"——只有一个集合（07-memory §7.8.1 规则 7⑧）。
 */
public final class FeedbackInput {

    private final StepId stepId;
    private final List<ExperienceId> experienceIds;

    public FeedbackInput(StepId stepId, List<ExperienceId> experienceIds) {
        this.stepId = stepId;
        this.experienceIds = immutable(experienceIds);
    }

    private static List<ExperienceId> immutable(List<ExperienceId> source) {
        return Collections.unmodifiableList(new ArrayList<ExperienceId>(
                source == null ? Collections.<ExperienceId>emptyList() : source));
    }

    public StepId stepId() {
        return stepId;
    }

    public List<ExperienceId> experienceIds() {
        return experienceIds;
    }
}
