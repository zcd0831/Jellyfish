package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划状态机：严格串行、SUCCEEDED 固化、重划只换尾部、终态不可再推。
 * 规则出处：模块-规划 §7.1、§3.1。
 */
class PlanTest {

    private static PlanStep step(int sequence) {
        return PlanSteps.draft(StepId.of("step" + sequence), sequence, "第 " + sequence + " 步",
                EnumSet.of(VerificationType.DETERMINISTIC));
    }

    private static Plan plan(int steps) {
        PlanStep[] array = new PlanStep[steps];
        for (int i = 0; i < steps; i++) {
            array[i] = step(i + 1);
        }
        return Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Arrays.asList(array));
    }

    @Test
    void 没有步骤不能建计划() {
        assertThrows(PlanCreationException.class,
                () -> Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.<PlanStep>emptyList()));
    }

    @Test
    void 当前步唯一且启动后进入RUNNING() {
        Plan plan = plan(3);
        assertEquals(StepStatus.PENDING, plan.getCurrentStep().status());

        Attempt attempt = plan.beginCurrentStep();

        assertEquals(StepStatus.RUNNING, plan.getCurrentStep().status());
        assertEquals(1, attempt.attemptNo());
        assertNull(attempt.recordRange(), "开区间：区间由外循环随后延伸");
        long running = plan.steps().stream().filter(s -> s.status() == StepStatus.RUNNING).count();
        assertEquals(1, running);
    }

    @Test
    void 全部成功即COMPLETED并raise事件() {
        Plan plan = plan(2);
        plan.pullEvents();

        plan.beginCurrentStep();
        plan.markStepSucceeded(StepId.of("step1"), "第一步完成", null);
        plan.beginCurrentStep();
        plan.markStepSucceeded(StepId.of("step2"), "第二步完成", null);

        assertEquals(PlanStatus.COMPLETED, plan.status());
        assertEquals(1, plan.pullEvents().size());
        assertEquals("第一步完成", plan.steps().get(0).finalOutput());
        assertTrue(plan.getCurrentStep() == null);
    }

    @Test
    void 判败raise_StepFailed() {
        Plan plan = plan(1);
        plan.pullEvents();
        plan.beginCurrentStep();

        plan.markStepFailed(StepId.of("step1"), null);

        assertEquals(StepStatus.FAILED, plan.steps().get(0).status());
        assertEquals(AttemptOutcome.FAILED, plan.steps().get(0).attempts().get(0).outcome());
        assertEquals("step.failed", plan.pullEvents().get(0).name());
    }

    @Test
    void 重划只替换未执行尾部且代次加一() {
        Plan plan = plan(2);
        plan.beginCurrentStep();
        plan.markStepSucceeded(StepId.of("step1"), "完成", null);

        plan.applyReplan(Collections.singletonList(PlanSteps.draft(StepId.of("new"), 0, "新的一步",
                EnumSet.noneOf(VerificationType.class))));

        assertEquals(1, plan.replanGeneration());
        assertEquals(StepStatus.SUCCEEDED, plan.steps().get(0).status());
        assertEquals(StepStatus.SUPERSEDED, plan.steps().get(1).status());
        assertEquals(3, plan.steps().size());
        assertEquals(StepStatus.PENDING, plan.getCurrentStep().status());
    }

    @Test
    void 终结后不可再推进() {
        Plan plan = plan(1);
        plan.pullEvents();

        plan.terminate("重划用尽");

        assertEquals(PlanStatus.FAILED, plan.status());
        assertEquals("plan.failed", plan.pullEvents().get(0).name());
        assertThrows(DomainInvariantViolation.class, () -> plan.terminate("再来一次"));
        assertThrows(DomainInvariantViolation.class, plan::beginCurrentStep);
    }

    @Test
    void 中断只落attempt结局且不改变步骤状态_candidates() {
        Plan plan = plan(1);
        Attempt attempt = plan.beginCurrentStep();

        plan.markAttemptInterrupted(StepId.of("step1"), attempt.attemptNo());

        assertEquals(AttemptOutcome.INTERRUPTED, attempt.outcome());
        assertEquals(StepStatus.RUNNING, plan.steps().get(0).status());
        assertNotNull(plan.getCurrentStep(), "挂起：下次输入继续同一步");
    }
}
