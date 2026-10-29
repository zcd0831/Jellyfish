package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计划聚合根：严格串行推进，完成即固化。
 * 规则出处：06-planning §6.4.1、§6.8.1。
 * <p>「还能不能重试 / 该不该重划」由外循环判断，聚合只保证状态机自身的合法迁移（06-planning §6.8.1）。
 */
public class Plan {

    private final PlanId id;
    private final SessionId sessionId;
    private final String goal;
    private final List<PlanStep> steps;
    private final List<DomainEvent> pendingEvents = new ArrayList<DomainEvent>();

    private PlanStatus status;
    private int replanGeneration;
    private String terminationReason;

    private Plan(PlanId id, SessionId sessionId, String goal, List<PlanStep> steps, PlanStatus status,
                 int replanGeneration) {
        this.id = id;
        this.sessionId = sessionId;
        this.goal = goal;
        this.steps = steps;
        this.status = status;
        this.replanGeneration = replanGeneration;
    }

    /** 规则出处：§7.1 规则 1（steps 非空）。 */
    public static Plan create(PlanId id, SessionId sessionId, String goal, List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new PlanCreationException(PlanningError.INVALID_PLAN, "计划至少要有一个步骤");
        }
        Plan plan = new Plan(id, sessionId, goal, new ArrayList<PlanStep>(steps), PlanStatus.EXECUTING, 0);
        plan.pendingEvents.add(new PlanCreated());
        return plan;
    }

    /** 续跑重建：不重放事件。 */
    public static Plan rehydrate(PlanId id, SessionId sessionId, String goal, PlanStatus status,
                                 int replanGeneration, List<PlanStep> steps) {
        return new Plan(id, sessionId, goal, new ArrayList<PlanStep>(steps), status, replanGeneration);
    }

    /** 取当前步：最多一个 RUNNING；否则第一条 PENDING。规则出处：§7.1 规则 2。 */
    public PlanStep getCurrentStep() {
        PlanStep pending = null;
        for (PlanStep step : steps) {
            if (step.status() == StepStatus.RUNNING || step.status() == StepStatus.VERIFYING) {
                return step;
            }
            if (pending == null && step.status() == StepStatus.PENDING) {
                pending = step;
            }
        }
        return pending;
    }

    public boolean hasExecutablePlan() {
        return status == PlanStatus.EXECUTING && getCurrentStep() != null;
    }

    /** 步骤启动：为当前步建 attempt（开区间）。 */
    public Attempt beginCurrentStep() {
        requireExecuting();
        PlanStep step = getCurrentStep();
        if (step == null) {
            throw new DomainInvariantViolation("计划没有可执行的步骤：" + id.getValue());
        }
        return step.beginAttempt();
    }

    /** 规则出处：§7.1 规则 4（SUCCEEDED 之后不再变更）。 */
    public void markStepSucceeded(StepId stepId, String summary, VerificationReportId reportId) {
        requireExecuting();
        PlanStep step = requireStep(stepId);
        step.markSucceeded(summary, reportId);
        if (allSucceeded()) {
            this.status = PlanStatus.COMPLETED;
            pendingEvents.add(new PlanCompleted());
        }
    }

    public void markStepFailed(StepId stepId, VerificationReportId reportId) {
        requireExecuting();
        PlanStep step = requireStep(stepId);
        step.currentAttempt().attachReport(reportId);
        step.currentAttempt().finish(AttemptOutcome.FAILED);
        step.markFailed();
        pendingEvents.add(new StepFailed());
    }

    /**
     * 延伸 attempt 的执行区间：每段 ReAct 结束（收敛 / 挂起 / 环境故障）由外循环调用一次，多段合并成一条连续区间。
     * 规则出处：06-planning §6.8.1 规则 10、§5.2（收敛但未提交时区间继续延伸）。
     */
    public void extendAttemptRange(StepId stepId, int attemptNo, RecordRange range) {
        if (range == null) {
            return;
        }
        PlanStep step = requireStep(stepId);
        for (Attempt attempt : step.attempts()) {
            if (attempt.attemptNo() == attemptNo) {
                attempt.extendRange(range);
                return;
            }
        }
        throw new DomainInvariantViolation("计划里没有该尝试：" + stepId.getValue() + "#" + attemptNo);
    }

    /** 撞轮次上限：只落 attempt 结局，重试/重划由外循环判定。 */
    public void markAttemptFailed(StepId stepId, int attemptNo) {
        PlanStep step = requireStep(stepId);
        for (Attempt attempt : step.attempts()) {
            if (attempt.attemptNo() == attemptNo) {
                attempt.finish(AttemptOutcome.FAILED);
                step.markFailed();
                return;
            }
        }
        throw new DomainInvariantViolation("计划里没有该尝试：" + stepId.getValue() + "#" + attemptNo);
    }

    /** 步骤重试：新建 Attempt，同一步回到 RUNNING。 */
    public Attempt retryStep(StepId stepId) {
        requireExecuting();
        PlanStep step = requireStep(stepId);
        step.markRunning();
        return step.beginAttempt();
    }

    /** 反思挂到指定 attempt：只写引用，不改结局、不消耗重试。 */
    public void recordReflection(StepId stepId, int attemptNo, io.github.zcd0831.jellyfish.domain.shared.ReflectionId reflectionId) {
        PlanStep step = requireStep(stepId);
        for (Attempt attempt : step.attempts()) {
            if (attempt.attemptNo() == attemptNo) {
                attempt.attachReflection(reflectionId);
                return;
            }
        }
        throw new DomainInvariantViolation("计划里没有该尝试：" + stepId.getValue() + "#" + attemptNo);
    }

    /** 技能使用痕迹：只写引用，供计划完结时判定"要不要改进"（07-memory §7.9.6）。 */
    public void recordSkillUsage(StepId stepId, int attemptNo, SkillId skillId) {
        PlanStep step = requireStep(stepId);
        for (Attempt attempt : step.attempts()) {
            if (attempt.attemptNo() == attemptNo) {
                attempt.recordSkillUsage(skillId);
                return;
            }
        }
        throw new DomainInvariantViolation("计划里没有该尝试：" + stepId.getValue() + "#" + attemptNo);
    }

    public void markStepVerifying(StepId stepId) {
        requireStep(stepId).markVerifying();
    }

    public void assignExperiences(StepId stepId, List<ExperienceId> experienceIds) {
        requireStep(stepId).assignExperiences(experienceIds);
    }

    /**
     * 重划：只替换未执行的尾部，被替换者置 SUPERSEDED 并保留，代次 +1。
     * 规则出处：06-planning §6.8.1 规则 5。
     */
    public void applyReplan(List<PlanStep> newTail) {
        requireExecuting();
        if (newTail == null || newTail.isEmpty()) {
            throw new PlanCreationException(PlanningError.INVALID_PLAN, "重划必须给出至少一个步骤");
        }
        for (PlanStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                step.markSuperseded();
            }
        }
        int sequence = steps.size();
        for (PlanStep step : newTail) {
            steps.add(PlanSteps.draft(step.id(), ++sequence, step.description(), step.goal(),
                    step.expectedOutcome(), step.verificationCriteria(), step.requiredVerifications()));
        }
        this.replanGeneration++;
        this.status = PlanStatus.EXECUTING;
    }

    /** 规则出处：§7.1 规则 6（COMPLETED / FAILED 都是终态）。 */
    public void terminate(String reason) {
        if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED) {
            throw new DomainInvariantViolation("计划已终结：" + id.getValue());
        }
        this.status = PlanStatus.FAILED;
        this.terminationReason = reason;
        pendingEvents.add(new PlanFailed());
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<DomainEvent>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    private boolean allSucceeded() {
        for (PlanStep step : steps) {
            if (step.status() != StepStatus.SUCCEEDED && step.status() != StepStatus.SUPERSEDED) {
                return false;
            }
        }
        return true;
    }

    private PlanStep requireStep(StepId stepId) {
        for (PlanStep step : steps) {
            if (step.id().equals(stepId)) {
                return step;
            }
        }
        throw new DomainInvariantViolation("计划里没有该步骤：" + stepId.getValue());
    }

    private void requireExecuting() {
        if (status != PlanStatus.EXECUTING && status != PlanStatus.REPLANNING) {
            throw new DomainInvariantViolation("计划不在执行态：" + status);
        }
    }

    public PlanId id() {
        return id;
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public String goal() {
        return goal;
    }

    public PlanStatus status() {
        return status;
    }

    public int replanGeneration() {
        return replanGeneration;
    }

    public List<PlanStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public String terminationReason() {
        return terminationReason;
    }
}
