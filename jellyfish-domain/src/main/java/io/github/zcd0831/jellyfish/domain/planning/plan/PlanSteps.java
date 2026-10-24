package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.util.Set;

/**
 * `PlanStep` 的构造入口：实体本身包内可见，只能经聚合根入口使用（D03 规则 3）。
 * 规则出处：模块-规划 §5。
 */
public final class PlanSteps {

    private PlanSteps() {
    }

    public static PlanStep draft(StepId id, int sequence, String description,
                                 Set<VerificationType> requiredVerifications) {
        return new PlanStep(id, sequence, description, requiredVerifications, StepStatus.PENDING);
    }

    public static PlanStep rehydrate(StepId id, int sequence, String description,
                                     Set<VerificationType> requiredVerifications, StepStatus status) {
        return new PlanStep(id, sequence, description, requiredVerifications, status);
    }
}
