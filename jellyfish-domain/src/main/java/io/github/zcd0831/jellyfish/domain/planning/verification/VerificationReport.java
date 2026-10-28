package io.github.zcd0831.jellyfish.domain.planning.verification;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.List;

/**
 * 外循环对某次 attempt 的判定结论：只追加、不可变、只留失败层与原因。
 * 规则出处：06-planning §6.4.2、§6.8.2。
 */
public final class VerificationReport {

    private final VerificationReportId id;
    private final PlanId planId;
    private final StepId stepId;
    private final int attemptNo;
    private final VerificationOutcome outcome;
    private final VerificationType failedType;
    private final String failureReason;
    private final long decidedAt;
    private final List<DomainEvent> pendingEvents = new ArrayList<DomainEvent>();

    private VerificationReport(VerificationReportId id, PlanId planId, StepId stepId, int attemptNo,
                               VerificationOutcome outcome, VerificationType failedType,
                               String failureReason, long decidedAt) {
        this.id = id;
        this.planId = planId;
        this.stepId = stepId;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.failedType = failedType;
        this.failureReason = failureReason;
        this.decidedAt = decidedAt;
    }

    /** 规则出处：§6（FAILED / UNDETERMINED 时 raise VerificationRejected）。 */
    public static VerificationReport decide(VerificationReportId id, PlanId planId, StepId stepId,
                                            int attemptNo, VerificationOutcome outcome,
                                            VerificationType failedType, String failureReason, long now) {
        VerificationReport report = new VerificationReport(id, planId, stepId, attemptNo, outcome, failedType,
                failureReason, now);
        if (outcome != VerificationOutcome.PASSED) {
            report.pendingEvents.add(new VerificationRejected());
        }
        return report;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<DomainEvent>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public VerificationReportId id() {
        return id;
    }

    public PlanId planId() {
        return planId;
    }

    public StepId stepId() {
        return stepId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public VerificationOutcome outcome() {
        return outcome;
    }

    public VerificationType failedType() {
        return failedType;
    }

    public String failureReason() {
        return failureReason;
    }

    public long decidedAt() {
        return decidedAt;
    }
}
