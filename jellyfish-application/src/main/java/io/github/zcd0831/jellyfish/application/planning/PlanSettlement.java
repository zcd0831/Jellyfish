package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.memory.DistillationInput;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.StepTrace;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 步骤与计划的"结算"：奖惩反馈、技能使用痕迹、计划完结后的沉淀。
 * <p>三件事都只写副作用、不推进状态机，所以从循环里拿出来；记忆模块不遍历 Plan，
 * 摘要由这里组装好再交出去。
 * 规则出处：07-memory §7.9.1/§7.9.3/§7.9.6、06-planning §6.6。
 */
final class PlanSettlement {

    private final ExperienceFeedbackService experienceFeedback;
    private final PlanCompletionCurator planCompletionCurator;
    private final ReflectionRepository reflections;
    private final CommitScope commitScope;

    PlanSettlement(ExperienceFeedbackService experienceFeedback, PlanCompletionCurator planCompletionCurator,
                   ReflectionRepository reflections, CommitScope commitScope) {
        this.experienceFeedback = experienceFeedback;
        this.planCompletionCurator = planCompletionCurator;
        this.reflections = reflections;
        this.commitScope = commitScope;
    }

    void stepSucceeded(PlanStep step) {
        experienceFeedback.onStepSucceeded(PlanProgress.feedbackOf(step));
    }

    void stepFailed(PlanStep step) {
        experienceFeedback.onStepFailed(PlanProgress.feedbackOf(step));
    }

    /** 改进判定要"这次用过的技能"：内循环只认识 PlanStep，痕迹落在 attempt 上。 */
    void recordSkillUsage(Plan plan, PlanStep step, Attempt attempt, IterationResult iteration) {
        if (plan == null || step == null || attempt == null || iteration.usedSkillIds().isEmpty()) {
            return;
        }
        for (SkillId skillId : iteration.usedSkillIds()) {
            plan.recordSkillUsage(step.id(), attempt.attemptNo(), skillId);
        }
        commitScope.stageReplace(plan);
        commitScope.commit();
    }

    /** 计划完结（含终局失败）：组装事实摘要交给编排者。 */
    void curate(Session session, Plan plan) {
        List<StepTrace> traces = new ArrayList<StepTrace>();
        for (PlanStep step : plan.steps()) {
            Attempt latest = step.attempts().isEmpty() ? null
                    : step.attempts().get(step.attempts().size() - 1);
            traces.add(new StepTrace(step.id(), step.description(), step.status(),
                    latest == null ? Collections.<SkillId>emptyList() : latest.usedSkillIds(),
                    latest == null || latest.outcome() == AttemptOutcome.SUCCEEDED ? null
                            : plan.terminationReason(),
                    latest == null || latest.reflectionId() == null ? null : reflectionOf(latest)));
        }
        planCompletionCurator.curate(session, new DistillationInput(plan.id(), session.id(), plan.goal(),
                plan.status(), traces));
    }

    private String reflectionOf(Attempt attempt) {
        java.util.Optional<Reflection> found = reflections.get(attempt.reflectionId());
        return found.isPresent() ? found.get().conclusion() : null;
    }
}
