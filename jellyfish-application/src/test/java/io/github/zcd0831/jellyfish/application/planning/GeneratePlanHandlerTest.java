package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
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
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
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

    private final ExperienceRetriever retriever = mock(ExperienceRetriever.class);

    private GeneratePlanHandler handler() {
        when(catalog.agents()).thenReturn(Collections.emptyList());
        when(retriever.retrieveAndAssign(any(Session.class), any())).thenReturn(Collections.emptyMap());
        return new GeneratePlanHandler(new PlanFactory(), plans, catalog, commitScope, events -> { },
                new ExperienceAssigner(retriever));
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

    /**
     * 经验必须与计划**同一次落盘**：诞生后同一次响应里再出错或被终止，已经没有第二次机会。
     * 这里取的是 stageReplace 那一刻的对象，不是事后引用（判败用例踩过引用陷阱）。
     * 规则出处：06-planning §6.8.1 规则 6、§6.9.4、07-memory §7.9.1。
     */
    @Test
    void 落盘那一刻经验已经分配好() {
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.<Plan>emptyList());
        GeneratePlanHandler underTest = handler();   // 先建 handler，它的默认桩会被下面的覆盖
        final java.util.Map<StepId, java.util.List<ExperienceId>> assignment =
                new LinkedHashMap<StepId, java.util.List<ExperienceId>>();
        when(retriever.retrieveAndAssign(any(Session.class), any())).thenAnswer(invocation -> {
            io.github.zcd0831.jellyfish.application.memory.RetrievalInput input =
                    invocation.getArgument(1);
            // 按真实检索器的口径：给每个待做步骤分配一条经验
            for (io.github.zcd0831.jellyfish.application.memory.StepBrief brief : input.steps()) {
                assignment.put(brief.stepId(), Collections.singletonList(ExperienceId.of("e1")));
            }
            return assignment;
        });
        final java.util.List<Integer> assignedAtCommit = new java.util.ArrayList<Integer>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Plan staged = invocation.getArgument(0);
            assignedAtCommit.add(staged.steps().get(0).assignedExperienceIds().size());
            return null;
        }).when(commitScope).stageReplace(any(Plan.class));

        underTest.handle(session, draft());

        assertEquals(Collections.singletonList(1), assignedAtCommit,
                "stageReplace 那一刻就该有经验；放到外循环去补，崩溃/终止后就永远没有");
    }

    @Test
    void 没有执行中的计划时正常创建() {
        when(plans.findRunning(any(SessionId.class))).thenReturn(Collections.<Plan>emptyList());

        ToolExecOutcome outcome = handler().handle(session, draft());

        assertTrue(outcome.content().contains("计划已创建"), outcome.content());
        verify(commitScope).stageReplace(any(Plan.class));
    }
}
