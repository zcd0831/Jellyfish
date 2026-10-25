package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.memory.DistillationInput;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 三条出口 + 重试 / 重划 / 终止 / 请求超限补折 / 反思。规则出处：模块-应用层 §8.1、§9。 */
class ExecutionLoopTest {

    private final ContextWindowService contextWindow = mock(ContextWindowService.class);
    private final ReActLoop reActLoop = mock(ReActLoop.class);
    private final VerificationRunner verificationRunner = mock(VerificationRunner.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final ExperienceRetriever retriever = mock(ExperienceRetriever.class);
    private final ExperienceFeedbackService feedback = mock(ExperienceFeedbackService.class);
    private final PlanCompletionCurator curator = mock(PlanCompletionCurator.class);
    private final ReflectionRepository reflections = mock(ReflectionRepository.class);
    private final ExperienceRepository experiences = mock(ExperienceRepository.class);

    private final Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"),
            ModelId.of("m1"), 1L, null);

    private RuntimePolicy policy;
    private final Deque<Plan> planScript = new ArrayDeque<Plan>();

    @BeforeEach
    void setUp() {
        policy = RuntimePolicy.defaults();
        when(policies.load()).thenReturn(policy);
        when(retriever.retrieveAndAssign(any(Session.class), any())).thenReturn(Collections.emptyMap());
        when(catalog.agents()).thenReturn(Collections.emptyList());
        when(reflections.get(any())).thenReturn(java.util.Optional.empty());
    }

    private ExecutionLoop loop() {
        // 每个用例可能改过策略：这里重新绑一次，避免用到 setUp 时的旧快照
        when(policies.load()).thenReturn(policy);
        when(plans.findRunning(SessionId.of("s1"))).thenAnswer(
                invocation -> planScript.isEmpty() ? Collections.<Plan>emptyList()
                        : Collections.singletonList(planScript.peek()));
        return new ExecutionLoop(contextWindow, reActLoop, verificationRunner, plans, new io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory(),
                catalog, policies, commitScope, events -> { }, event -> { }, model, budget, retriever,
                feedback, curator, reflections, experiences);
    }

    /** 默认实现不读上下文（依赖由构造器注入），所以这里给空值即可。 */
    private static final CancellationToken NO_TOKEN = CancellationToken.never();
    private static final RunContext NO_CONTEXT = new RunContext(null, null, NO_TOKEN, null, null, null, null,
            null, null);

    private static Plan plan(VerificationType... layers) {
        Plan plan = Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "做事", "g", "o", "c",
                        layers.length == 0 ? EnumSet.noneOf(VerificationType.class) : EnumSet.of(layers[0]))));
        return plan;
    }

    private static IterationResult converged() {
        return IterationResult.converged(1, java.util.Collections.emptyList());
    }

    private static IterationResult submitted(String summary) {
        return IterationResult.submitted(summary, 1, java.util.Collections.emptyList());
    }

    private static VerificationReport passed() {
        return VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"), StepId.of("step1"), 1,
                VerificationOutcome.PASSED, null, null, 1L);
    }

    private static VerificationReport failed() {
        return VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"), StepId.of("step1"), 1,
                VerificationOutcome.FAILED, VerificationType.DETERMINISTIC, "不合格", 1L);
    }

    @Test
    void 无计划且收敛即结束() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenReturn(converged());

        assertEquals(RunOutcome.CONVERGED, loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));
    }

    @Test
    void 无计划且撞轮次上限返回轮次用尽() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(IterationResult.notConverged(20, java.util.Collections.emptyList()));

        assertEquals(RunOutcome.ROUNDS_EXHAUSTED, loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));
    }

    @Test
    void 撞轮次上限时判当前attempt失败而不是按轮次号猜() {
        policy = policy.adjust(Collections.<String, Object>singletonMap("maxRetries", 3));
        Plan plan = plan();
        planScript.add(plan);
        // 先撞上限（第 20 轮），重试后模型不调工具 → 挂起
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(IterationResult.notConverged(20, java.util.Collections.emptyList()))
                .thenReturn(converged());

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertEquals(AttemptOutcome.FAILED, plan.steps().get(0).attempts().get(0).outcome(),
                "撞轮次上限要落到当前 attempt 上（轮次号 20 与 attempt 号无关）");
        assertEquals(2, plan.steps().get(0).attempts().size(), "失败后应重试同一步");
    }

    @Test
    void 有计划但未提交即挂起() {
        planScript.add(plan());
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenReturn(converged());

        assertEquals(RunOutcome.SUSPENDED, loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));
    }

    @Test
    void 免验证步骤提交即完成并在完结后跑一轮自由内循环() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"))
                .thenReturn(converged());

        RunOutcome outcome = loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertEquals(RunOutcome.CONVERGED, outcome);
        assertEquals(PlanStatus.COMPLETED, plan.status());
        verify(curator).curate(any(Session.class), any(DistillationInput.class));
        verify(feedback).onStepSucceeded(any());
    }

    @Test
    void 每段ReAct结束都把新增记录区间写进attempt() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(invocation -> {
            Session running = invocation.getArgument(0);
            running.appendRecord(io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.USER,
                    "继续", null, 2L);
            return converged();
        });

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertTrue(plan.steps().get(0).attempts().get(0).recordRange() != null,
                "挂起也要把已发生的轨迹记进 attempt");
        assertEquals(plan.steps().get(0).attempts().get(0).recordRange().getStartRecordId(),
                plan.steps().get(0).attempts().get(0).recordRange().getEndRecordId(), "只有一条记录时区间退化为点");
    }

    @Test
    void 环境故障也把中止记录记进区间且不判失败() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(invocation -> {
            Session running = invocation.getArgument(0);
            running.appendRecord(io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.TOOL_REQUEST,
                    "{\"callId\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}", null, 2L);
            running.appendRecord(io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.TOOL_RESULT,
                    "（本轮中止）", null, 3L);
            throw new ModelInvocationException(ModelError.UNAVAILABLE, "挂了");
        });

        assertThrows(ModelInvocationException.class,
                () -> loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));

        assertTrue(plan.steps().get(0).attempts().get(0).recordRange() != null);
        assertEquals(StepStatus.RUNNING, plan.steps().get(0).status(), "环境故障不推进步骤状态");
        assertNull(plan.steps().get(0).attempts().get(0).outcome(), "attempt 不落终态");
    }

    @Test
    void 验证通过后完成并落地报告() {
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any()))
                .thenReturn(passed());

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertEquals(PlanStatus.COMPLETED, plan.status());
        verify(commitScope).stageFact(any(VerificationReport.class));
    }

    @Test
    void 验证失败但未超重试次数则重试同一步() {
        policy = policy.adjust(Collections.<String, Object>singletonMap("maxRetries", 3));
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"))
                .thenReturn(submitted("做完了"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any()))
                .thenReturn(failed())
                .thenReturn(passed());

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertEquals(PlanStatus.COMPLETED, plan.status());
        assertEquals(2, plan.steps().get(0).attempts().size(), "失败后应重试同一步");
        verify(feedback).onStepFailed(any());
    }

    @Test
    void 重试用尽则重划_重划成功后继续执行() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 1);
            put("maxReplanGeneration", 1);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"))
                .thenReturn(submitted("第二次"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any()))
                .thenReturn(failed())
                .thenReturn(passed());
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(new ModelInvocationResult(
                "{\"steps\":[{\"description\":\"换个做法\",\"goal\":\"g\",\"expectedOutcome\":\"o\","
                        + "\"verificationCriteria\":\"c\"}]}", Collections.emptyList(),
                new ModelUsage(1, 1), ModelId.of("m1")));

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertTrue(plan.replanGeneration() >= 1, "应发生重划");
    }

    @Test
    void 重划也用尽则终止并沉淀() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 1);
            put("maxReplanGeneration", 0);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"))
                .thenReturn(submitted("第二次"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any()))
                .thenReturn(failed())
                .thenReturn(failed());

        RunOutcome outcome = loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        assertEquals(RunOutcome.CONVERGED, outcome);
        assertEquals(PlanStatus.FAILED, plan.status());
        verify(curator).curate(any(Session.class), any(DistillationInput.class));
    }

    @Test
    void 请求超限时强制折叠一次后继续() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenThrow(new ModelInvocationException(ModelError.CONTEXT_EXCEEDED, "超限"))
                .thenReturn(converged());

        assertEquals(RunOutcome.CONVERGED, loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));
        verify(contextWindow).forceCollapse(session);
    }

    @Test
    void 模型不可用时原样抛出且不改状态() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenThrow(new ModelInvocationException(ModelError.UNAVAILABLE, "全挂"));

        assertThrows(ModelInvocationException.class, () -> loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT));
        verify(commitScope, never()).stageReplace(any());
    }

    @Test
    void 达反思阈值时调内置反思代理并落反思() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 3);
            put("reflectionThreshold", 1);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"))
                .thenReturn(submitted("第二次"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any()))
                .thenReturn(failed())
                .thenReturn(passed());
        when(catalog.agents()).thenReturn(Collections.singletonList(reflectAgent()));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(new ModelInvocationResult(
                "下次先读文件", Collections.emptyList(), new ModelUsage(1, 1), ModelId.of("m1")));

        loop().run(session, ChunkSink.NOOP, NO_TOKEN, NO_CONTEXT);

        verify(commitScope, times(1)).stageFact(any(io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection.class));
        verify(budget).recordUsage(any(Session.class), org.mockito.ArgumentMatchers.anyLong());
    }

    private static io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition reflectAgent() {
        return new io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition(
                AgentId.of("builtin.reflect"), "反思代理",
                io.github.zcd0831.jellyfish.domain.action.agent.AgentSource.BUILT_IN,
                new io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate("你是复盘者", java.util.Collections.<String>emptyList()),
                io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus.ACTIVE,
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }
}
