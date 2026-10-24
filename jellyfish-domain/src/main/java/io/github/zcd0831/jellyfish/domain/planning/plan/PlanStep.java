package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 计划中的一步。规则出处：模块-规划 §5、§7.1。 */
public class PlanStep {

    private final StepId id;
    private final int sequence;
    private final String description;
    private final Set<VerificationType> requiredVerifications;
    private final List<Attempt> attempts = new ArrayList<Attempt>();
    private final List<ExperienceId> assignedExperienceIds = new ArrayList<ExperienceId>();

    private StepStatus status;
    private String finalOutput;
    private VerificationReportId finalVerificationReportId;

    PlanStep(StepId id, int sequence, String description, Set<VerificationType> requiredVerifications,
             StepStatus status) {
        this.id = id;
        this.sequence = sequence;
        this.description = description;
        this.requiredVerifications = Collections.unmodifiableSet(new HashSet<VerificationType>(
                requiredVerifications == null ? Collections.<VerificationType>emptySet() : requiredVerifications));
        this.status = status;
    }

    /** 步骤启动：创建开区间 attempt（§8.1）。 */
    Attempt beginAttempt() {
        Attempt attempt = new Attempt(attempts.size() + 1, null);
        attempts.add(attempt);
        this.status = StepStatus.RUNNING;
        return attempt;
    }

    Attempt currentAttempt() {
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    void markSucceeded(String summary, VerificationReportId reportId) {
        this.status = StepStatus.SUCCEEDED;
        this.finalOutput = summary;
        this.finalVerificationReportId = reportId;
    }

    void markFailed() {
        this.status = StepStatus.FAILED;
    }

    void markSuperseded() {
        this.status = StepStatus.SUPERSEDED;
    }

    void markVerifying() {
        this.status = StepStatus.VERIFYING;
    }

    void markRunning() {
        this.status = StepStatus.RUNNING;
    }

    void assignExperiences(List<ExperienceId> experienceIds) {
        assignedExperienceIds.clear();
        assignedExperienceIds.addAll(experienceIds);
    }

    /** 规则出处：§7.1 规则 3（免验证步骤提交即完成）。 */
    boolean needsVerification() {
        return !requiredVerifications.isEmpty();
    }

    public StepId id() {
        return id;
    }

    public int sequence() {
        return sequence;
    }

    public String description() {
        return description;
    }

    public Set<VerificationType> requiredVerifications() {
        return requiredVerifications;
    }

    public StepStatus status() {
        return status;
    }

    public List<Attempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    public List<ExperienceId> assignedExperienceIds() {
        return Collections.unmodifiableList(assignedExperienceIds);
    }

    public String finalOutput() {
        return finalOutput;
    }

    public VerificationReportId finalVerificationReportId() {
        return finalVerificationReportId;
    }
}
