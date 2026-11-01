package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** `replan_plan`：草案通过才替换未执行尾部；失败是工具结果。规则出处：05-action §5.4.1、06-planning §6.8.1 规则 6。 */
class ReplanPlanHandlerTest {

    private final PlanRepository plans = mock(PlanRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private ReplanPlanHandler handler() {
        when(catalog.agents()).thenReturn(Collections.emptyList());
        when(policies.load()).thenReturn(RuntimePolicy.defaults());
        return new ReplanPlanHandler(new PlanFactory(), plans, catalog, commitScope, policies);
    }

    private static Plan plan() {
        return Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "第一步", "g", "o", "c",
                        Collections.singleton(VerificationType.DETERMINISTIC))));
    }

    private static Map<String, Object> draft(String description) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("description", description);
        step.put("goal", "g");
        step.put("expectedOutcome", "o");
        step.put("verificationCriteria", "c");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("steps", Collections.singletonList(step));
        return args;
    }

    @Test
    void 重划替换未执行尾部并推进代次() {
        Plan plan = plan();
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.singletonList(plan));

        ToolExecOutcome outcome = handler().handle(session, draft("换个做法"));

        assertEquals(1, plan.replanGeneration());
        assertEquals(StepStatus.SUPERSEDED, plan.steps().get(0).status(), "旧尾部保留但不执行");
        assertEquals("换个做法", plan.getCurrentStep().description());
        assertTrue(outcome.content().contains("计划已重划（第 1 代）"), outcome.content());
    }

    @Test
    void 重划代次用尽时返回失败结果且不改计划() {
        Plan plan = plan();
        plan.applyReplan(Collections.singletonList(PlanSteps.draft(StepId.of("step2"), 0, "换", "g", "o", "c",
                Collections.singleton(VerificationType.DETERMINISTIC))));
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.singletonList(plan));
        ReplanPlanHandler handler = handler();
        when(policies.load()).thenReturn(RuntimePolicy.defaults().adjust(
                Collections.<String, Object>singletonMap("maxReplanGeneration", 1)));

        ToolExecOutcome outcome = handler.handle(session, draft("再来一次"));

        assertTrue(outcome.content().contains("重划已用尽"), outcome.content());
        assertEquals(1, plan.replanGeneration(), "被拒的重划不推进代次");
    }

    @Test
    void 没有运行中的计划时返回失败结果() {
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.<Plan>emptyList());

        assertEquals("没有可重划的计划", handler().handle(session, draft("换个做法")).content());
    }

    @Test
    void 草案不合法时返回失败结果且不改计划() {
        Plan plan = plan();
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.singletonList(plan));
        Map<String, Object> empty = new LinkedHashMap<String, Object>();
        empty.put("steps", Collections.emptyList());

        ToolExecOutcome outcome = handler().handle(session, empty);

        assertTrue(outcome.content().startsWith("重划被拒绝："), outcome.content());
        assertEquals(0, plan.replanGeneration());
        assertEquals(PlanStatus.EXECUTING, plan.status());
    }
}
