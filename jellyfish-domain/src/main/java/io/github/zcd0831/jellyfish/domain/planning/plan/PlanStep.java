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

/** 计划中的一步。规则出处：06-planning §6.6、§6.8.1。 */
public class PlanStep {

    private final StepId id;
    private final int sequence;
    private final String description;
    private final String goal;
    private final String expectedOutcome;
    private final String verificationCriteria;
    private final Set<VerificationType> requiredVerifications;
    private final List<Attempt> attempts = new ArrayList<Attempt>();
    private final List<ExperienceId> assignedExperienceIds = new ArrayList<ExperienceId>();

    private StepStatus status;
    private String finalOutput;
    private VerificationReportId finalVerificationReportId;

    PlanStep(StepId id, int sequence, String description, String goal, String expectedOutcome,
             String verificationCriteria, Set<VerificationType> requiredVerifications, StepStatus status) {
        this.id = id;
        this.sequence = sequence;
        this.description = description;
        this.goal = goal;
        this.expectedOutcome = expectedOutcome;
        this.verificationCriteria = verificationCriteria;
        this.requiredVerifications = Collections.unmodifiableSet(new HashSet<VerificationType>(
                requiredVerifications == null ? Collections.<VerificationType>emptySet() : requiredVerifications));
        this.status = status;
    }

    /** 步骤启动：创建开区间 attempt（§8.1）。 */
    Attempt beginAttempt() {
        Attempt attempt = new Attempt(attempts.size() + 1);
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

    /** 续跑重建：把落盘 attempts / 经验 / 终态装回实体（不重放任何迁移）。 */
    public void restoreAttempt(Attempt attempt) {
        attempts.add(attempt);
    }

    public void restoreExperience(ExperienceId experienceId) {
        assignedExperienceIds.add(experienceId);
    }

    public void restoreOutcome(String finalOutput, VerificationReportId reportId) {
        this.finalOutput = finalOutput;
        this.finalVerificationReportId = reportId;
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

    /** LLM 裁判要吃"原定目标"。 */
    public String goal() {
        return goal;
    }

    /** LLM 裁判要吃"结果期望"。 */
    public String expectedOutcome() {
        return expectedOutcome;
    }

    /** LLM 裁判要吃"验证标准"。 */
    public String verificationCriteria() {
        return verificationCriteria;
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
