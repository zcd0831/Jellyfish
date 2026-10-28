package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.StepId;

/**
 * 检索入参里的一个步骤摘要：检索子代理只需要"步号 + 描述"来匹配经验。
 * 规则出处：07-memory §7.6。
 */
public final class StepBrief {

    private final StepId stepId;
    private final String description;

    public StepBrief(StepId stepId, String description) {
        this.stepId = stepId;
        this.description = description;
    }

    public StepId stepId() {
        return stepId;
    }

    public String description() {
        return description;
    }
}
