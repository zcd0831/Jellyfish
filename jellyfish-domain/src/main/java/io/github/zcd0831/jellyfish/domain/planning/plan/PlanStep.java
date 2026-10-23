package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import java.util.List;
import java.util.Set;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

/** 规则出处：模块-规划 §6.6。 */
public class PlanStep {

    private StepId id;
    private int sequence;
    private String description;
    private Set<VerificationType> requiredVerifications;
    private StepStatus status;
    private List<Attempt> attempts;
    private List<ExperienceId> assignedExperienceIds;
    private String finalOutput;
    private VerificationReportId finalVerificationReportId;
}
