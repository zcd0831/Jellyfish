package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
/** 规则出处：模块-规划 §7.2。 */
public class VerificationReport {

    private VerificationReportId id;
    private PlanId planId;
    private StepId stepId;
    private int attemptNo;
    private VerificationOutcome outcome;
    private VerificationType failedType;
    private String failureReason;
    private long decidedAt;
}
