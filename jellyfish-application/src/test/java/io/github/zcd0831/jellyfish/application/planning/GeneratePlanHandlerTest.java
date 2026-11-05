package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** `generate_plan`：同一会话至多一个可执行计划。规则出处：06-planning §6.4.1 不变量 ⑥、§6.8.1 规则 1。 */
class GeneratePlanHandlerTest {

    private final PlanRepository plans = mock(PlanRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final Session session = Session.create(SessionHeader
            .builder(SessionId.of("s1"), AgentId.of("a"), ModelId.of("m1")).createdDate(1L).build());

    private GeneratePlanHandler handler() {
        when(catalog.agents()).thenReturn(Collections.emptyList());
        return new GeneratePlanHandler(new PlanFactory(), plans, catalog, commitScope, events -> { });
    }

    private static Plan runningPlan() {
        return Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "第一步", "g", "o", "c",
                        Collections.singleton(VerificationType.DETERMINISTIC))));
    }

    private static Map<String, Object> draft() {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("description", "第一步");
        step.put("goal", "g");
        step.put("expectedOutcome", "o");
        step.put("verificationCriteria", "c");
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("steps", Collections.singletonList(step));
        return args;
    }

    @Test
    void 已有执行中的计划时拒绝再生成() {
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.singletonList(runningPlan()));

        ToolExecOutcome outcome = handler().handle(session, draft());

        assertTrue(outcome.content().contains("已有一个执行中的计划"), outcome.content());
        verify(commitScope, never()).stageReplace(any());
    }

    @Test
    void 没有执行中的计划时正常创建() {
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.<Plan>emptyList());

        ToolExecOutcome outcome = handler().handle(session, draft());

        assertTrue(outcome.content().contains("计划已创建"), outcome.content());
        verify(commitScope).stageReplace(any(Plan.class));
    }
}
