package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
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
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
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
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 三条出口 + 重试 / 重划 / 终止 / 请求超限补折 / 反思。规则出处：08-application §8.9.1、§8.10。 */
class ExecutionLoopTest {

    private final ReActLoop reActLoop = mock(ReActLoop.class);
    private final VerificationRunner verificationRunner = mock(VerificationRunner.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final ExperienceRetriever retriever = mock(ExperienceRetriever.class);
    private final ExperienceFeedbackService feedback = mock(ExperienceFeedbackService.class);
    private final PlanCompletionCurator curator = mock(PlanCompletionCurator.class);
    private final ReflectionRepository reflections = mock(ReflectionRepository.class);
    private final ExperienceRepository experiences = mock(ExperienceRepository.class);
    private final io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks =
            mock(io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner.class);

    private final Session session = Session.create(SessionHeader
            .builder(SessionId.of("s1"), AgentId.of("a"), ModelId.of("m1")).createdDate(1L).build());

    /** 轮次边界的令牌真源：一个会话一个令牌（08-application §8.9.4.1）。 */
    private final SessionCancellations cancellations = new SessionCancellations();

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

    private final io.github.zcd0831.jellyfish.application.action.SessionToolScope scope =
            new io.github.zcd0831.jellyfish.application.action.SessionToolScope();
    private final java.util.List<io.github.zcd0831.jellyfish.application.shared.ProgressEvent> progressEvents =
            new java.util.ArrayList<io.github.zcd0831.jellyfish.application.shared.ProgressEvent>();

    private ExecutionLoop loop() {
        // 每个用例可能改过策略：这里重新绑一次，避免用到 setUp 时的旧快照
        when(policies.load()).thenReturn(policy);
        when(plans.findRunning(SessionId.of("s1"))).thenAnswer(
                invocation -> planScript.isEmpty() ? Collections.<Plan>emptyList()
                        : Collections.singletonList(planScript.peek()));
        return new ExecutionLoop(reActLoop, verificationRunner, plans,
                new io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory(),
                catalog, policies, commitScope, events -> { }, progressEvents::add, cancellations,
                feedback, curator, reflections, experiences, scope, forks);
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

    /**
     * 工具不响应终止（插件）时，收手点退化到下一次轮次边界：本轮结果照收，下一轮开头抛 CancellationException。
     * 规则出处：08-application §8.9.4.1（插件工具尽力而为，未响应则退化为下一次轮次边界）。
     */
    @Test
    void 不响应终止的在途工具退化到下一次轮次边界() {
        planScript.add(plan());
        cancellations.register(SessionId.of("s1"), null);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            cancellations.cancel(SessionId.of("s1"));   // 工具在跑的时候按了 Esc，但插件工具没响应
            return submitted("做完了");
        });

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
        verify(reActLoop, times(1)).runIteration(any(Session.class), any(), any(ChunkSink.class));
    }

    /**
     * 轮次边界只认登记表：本会话未登记（不在跑）就不该被别的会话的终止牵连，
     * 取不到令牌时一律是 <code>never()</code>。
     * 规则出处：08-application §8.9.4.1（轮次外的调用拿不到令牌，用 never()）。
     */
    @Test
    void 本会话未登记时别的会话终止不影响轮次边界() {
        cancellations.register(SessionId.of("other"), null);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            cancellations.cancel(SessionId.of("other"));   // 另一个在跑会话被终止
            return converged();
        });

        assertEquals(RunOutcome.CONVERGED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
    }

    @Test
    void 无计划且收敛即结束() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenReturn(converged());

        assertEquals(RunOutcome.CONVERGED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
    }

    @Test
    void 无计划且撞轮次上限提示后挂起() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(IterationResult.notConverged(20, java.util.Collections.emptyList()));

        assertEquals(RunOutcome.SUSPENDED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
        assertTrue(progressEvents.stream().anyMatch(e -> e.kind()
                        == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.ROUNDS_EXHAUSTED),
                "撞轮次上限要提示用户：" + progressEvents);
    }

    @Test
    void 撞轮次上限不判失败也不消耗重试() {
        policy = policy.adjust(Collections.<String, Object>singletonMap("maxRetries", 3));
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(IterationResult.notConverged(20, java.util.Collections.emptyList()));

        assertEquals(RunOutcome.SUSPENDED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));

        assertEquals(StepStatus.RUNNING, plan.steps().get(0).status(), "撞上限不判失败：step 仍 RUNNING");
        assertEquals(1, plan.steps().get(0).attempts().size(), "不消耗重试：不新建 attempt");
        assertNull(plan.steps().get(0).attempts().get(0).outcome(), "attempt 不落终态");
    }

    @Test
    void 有计划但未提交即挂起() {
        planScript.add(plan());
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenReturn(converged());

        assertEquals(RunOutcome.SUSPENDED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
    }

    @Test
    void 免验证步骤提交即完成并在完结后跑一轮自由内循环() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"))
                .thenReturn(converged());

        RunOutcome outcome = loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(RunOutcome.CONVERGED, outcome);
        assertEquals(PlanStatus.COMPLETED, plan.status());
        verify(curator).curate(any(Session.class), any(DistillationInput.class));
        verify(feedback).onStepSucceeded(any());
    }

    @Test
    void 环境故障补中止记录且不判失败() {
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
                () -> loop().run(session, ChunkSink.NOOP, NO_CONTEXT));

        assertTrue(plan.steps().get(0).attempts().get(0).usedSkillIds().isEmpty(), "环境故障不记录技能");
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
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(passed());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

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
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(passed());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(PlanStatus.COMPLETED, plan.status());
        assertEquals(2, plan.steps().get(0).attempts().size(), "失败后应重试同一步");
        verify(feedback).onStepFailed(any());
    }

    /**
     * 判败与随后的重试/纠偏/终止必须是**同一次**提交：拆成两次时，中间窗口盘上是
     * 「EXECUTING 但没有当前步」，续跑时外循环只能当自由轮收敛、generate_plan 又被
     * 「已有执行中的计划」拒绝 ⇒ 该会话再也规划不了。
     * 规则出处：08-application §8.8.2 T7（一次状态转移）、§8.8.1（恢复方向单向补齐）。
     */
    @Test
    void 判败与重试只提交一次_盘上永远有当前步() {
        policy = policy.adjust(Collections.<String, Object>singletonMap("maxRetries", 3));
        planScript.add(plan(VerificationType.DETERMINISTIC));
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"))
                .thenReturn(submitted("做完了"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(passed());

        // 必须"提交那一刻"取快照：捕获引用的话，后续 retryStep 会把已捕获的对象一起改掉
        final java.util.List<String> written = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object aggregate = invocation.getArgument(0);
            if (aggregate instanceof Plan) {
                Plan snapshot = (Plan) aggregate;
                written.add(snapshot.status() + "/"
                        + (snapshot.getCurrentStep() == null ? "无当前步"
                        : snapshot.getCurrentStep().id().getValue()));
            }
            return null;
        }).when(commitScope).stageReplace(any());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        for (String state : written) {
            assertFalse(state.equals(PlanStatus.EXECUTING + "/无当前步"),
                    "盘上出现 EXECUTING 却没有当前步：判败与重试被拆成了两次提交 " + written);
        }
        assertTrue(written.contains(PlanStatus.COMPLETED + "/无当前步"), written.toString());
    }

    /**
     * 降级换了模型必须让用户看见：内循环只改会话状态，播报在外循环。
     * 规则出处：08-application §8.6（MODEL_SWITCHED 属阶段类事件）。
     */
    @Test
    void 降级换模型时播报模型已切换() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            session.switchModel(ModelId.of("m2"));   // 模型端口降级到备用链
            return converged();
        });

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        boolean announced = false;
        for (io.github.zcd0831.jellyfish.application.shared.ProgressEvent event : progressEvents) {
            if (event.kind() == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.MODEL_SWITCHED) {
                announced = event.message().contains("m1") && event.message().contains("m2");
            }
        }
        assertTrue(announced, "降级要播报（带上旧模型与新模型）：" + progressEvents);
    }

    /**
     * 反思分支也不许自己提交一次：判败 → 反思 → 重试必须落在同一次提交里。
     * 反思自己 commit 时，盘上会出现「EXECUTING 但没有当前步」，崩溃续跑会把"本该重试"判成计划失败。
     * 规则出处：06-planning §6.8.1「无当前步即终结」、08-application §8.8.2 T7。
     */
    @Test
    void 开反思时判败与推进仍然只提交一次() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 3);
            put("reflectionThreshold", 1);
        }});
        planScript.add(plan(VerificationType.DETERMINISTIC));
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"))
                .thenReturn(submitted("第二次"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(passed());
        when(catalog.agents()).thenReturn(Collections.singletonList(reflectAgent()));
        when(forks.run(any(Session.class), any())).thenReturn("下次先读文件");
        final java.util.List<String> written = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object aggregate = invocation.getArgument(0);
            if (aggregate instanceof Plan) {
                Plan snapshot = (Plan) aggregate;
                written.add(snapshot.status() + "/" + (snapshot.getCurrentStep() == null ? "无当前步"
                        : snapshot.getCurrentStep().id().getValue()));
            }
            return null;
        }).when(commitScope).stageReplace(any());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        for (String state : written) {
            assertFalse(state.equals(PlanStatus.EXECUTING + "/无当前步"),
                    "反思自己 commit 了一次，盘上出现 EXECUTING 却没有当前步：" + written);
        }
        assertTrue(written.contains(PlanStatus.COMPLETED + "/无当前步"), written.toString());
    }

    /**
     * 事件在提交之后发：否则订阅方看到「步骤失败」时，盘上可能还没有这次判败。
     * 这里盯的是"报告事实落盘 + 提交 + 事件"这三步的相对顺序，不是随便一次 commit。
     * 规则出处：08-application §8.8.3 纪律 2。
     */
    @Test
    void 判败事件在报告事实提交之后才发出() {
        final java.util.List<String> order = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add("stageFact");
            return null;
        }).when(commitScope).stageFact(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add("commit");
            return null;
        }).when(commitScope).commit();
        ExecutionLoop loop = new ExecutionLoop(reActLoop, verificationRunner, plans,
                new io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory(), catalog, policies, commitScope,
                events -> order.add("publish"), progressEvents::add, cancellations,
                feedback, curator, reflections, experiences, scope, forks);
        policy = policy.adjust(Collections.<String, Object>singletonMap("maxRetries", 3));
        final Plan running = plan(VerificationType.DETERMINISTIC);
        planScript.add(running);
        when(plans.findRunning(SessionId.of("s1"))).thenReturn(Collections.singletonList(running));
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"))
                .thenReturn(converged());
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed());

        loop.run(session, ChunkSink.NOOP, NO_CONTEXT);

        int staged = order.indexOf("stageFact");
        assertTrue(staged >= 0, "报告事实要落盘：" + order);
        assertEquals(3, order.size() - staged > 2 ? 3 : order.size() - staged, order.toString());
        assertEquals("stageFact", order.get(staged), order.toString());
        assertEquals("commit", order.get(staged + 1), "事实之后必须紧接提交：" + order);
        assertEquals("publish", order.get(staged + 2), "事件要在提交之后发：" + order);
    }

    /**
     * 「EXECUTING 但没有当前步」由外循环自己收口，不押在启动对账上：
     * 否则同一次进程内这一轮就只会当自由轮收敛，计划永远挂在盘上。
     * 规则出处：06-planning §6.8.1「无当前步即终结」。
     */
    @Test
    void 计划无当前步时由外循环判失败并补交付轮() {
        Plan stuck = plan(VerificationType.DETERMINISTIC);
        stuck.beginCurrentStep();
        stuck.markStepFailed(StepId.of("step1"), VerificationReportId.of("r1"));   // 步已终结，计划还 EXECUTING
        planScript.add(stuck);
        when(plans.load(any(PlanId.class))).thenReturn(stuck);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenReturn(converged());

        RunOutcome outcome = loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(RunOutcome.CONVERGED, outcome);
        assertEquals(PlanStatus.FAILED, stuck.status(), "无当前步要收敛为失败，而不是留在 EXECUTING");
        assertNotNull(stuck.terminationReason(), "终止原因要落盘，续跑时才知道为什么停");
        verify(reActLoop, times(1)).runIteration(any(Session.class), org.mockito.ArgumentMatchers.isNull(),
                any(ChunkSink.class));   // 只跑交付轮（无 step）
        verify(curator).curate(any(Session.class), any(io.github.zcd0831.jellyfish.application.memory.DistillationInput.class));
    }

    @Test
    void 重试用尽先注入纠偏指令_模型重划成功后继续执行() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 1);
            put("maxReplanGeneration", 1);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            PlanStep current = call.getArgument(1);
            if (current == null) {
                return converged();   // 结论交付轮
            }
            if (calls.incrementAndGet() == 2) {
                // 纠偏 attempt 里模型主动重划：真实效果在 replan_plan 处理器里
                plan.applyReplan(Collections.singletonList(PlanSteps.draft(StepId.of("step2"), 0, "换个做法",
                        "g", "o", "c", Collections.singleton(VerificationType.DETERMINISTIC))));
            }
            return submitted("第 " + calls.get() + " 次");
        });
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(passed())
                .thenReturn(passed());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(1, plan.replanGeneration(), "应发生一次重划");
        assertEquals(2, plan.steps().get(0).attempts().size(), "纠偏 attempt 不占 maxRetries，只建一条");
        assertEquals(StepStatus.SUCCEEDED, plan.steps().get(0).status(),
                "重划只换未执行尾部，执行中的 step 提交仍然有效");
        assertTrue(session.records().stream().anyMatch(r ->
                        r.role() == io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.SYSTEM
                                && r.content() != null && r.content().startsWith("[重划纠偏 step=")),
                "重试用尽要注入纠偏指令");
        assertTrue(progressEvents.stream().anyMatch(e ->
                        e.kind() == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.PLAN_REPLAN
                                && e.message().contains("第 1 代")),
                "重划后要发 PLAN_REPLAN：" + progressEvents);
    }

    @Test
    void 纠偏attempt也未成功则终止并补结论交付轮() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 1);
            put("maxReplanGeneration", 3);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        java.util.concurrent.atomic.AtomicBoolean conclusionToolsBanned = new java.util.concurrent.atomic.AtomicBoolean();
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            if (call.getArgument(1) == null) {
                conclusionToolsBanned.set(!scope.allows(session.id(), ToolId.of("read_file"))
                        && !scope.allows(session.id(), ToolId.of("replan_plan")));
                return converged();
            }
            return submitted("做完了");
        });
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed());

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(PlanStatus.FAILED, plan.status());
        assertEquals(0, plan.replanGeneration(), "没换成新尾部就不该推进代次");
        assertEquals(2, plan.steps().get(0).attempts().size(), "每个 step 最多一次纠偏");
        assertTrue(conclusionToolsBanned.get(), "结论交付轮禁用全部工具");
    }

    @Test
    void 重划代次已用尽则不纠偏直接终止并沉淀() {
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
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(failed());

        RunOutcome outcome = loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertEquals(RunOutcome.CONVERGED, outcome);
        assertEquals(PlanStatus.FAILED, plan.status());
        verify(curator).curate(any(Session.class), any(DistillationInput.class));
    }

    @Test
    void 模型不可用时原样抛出且不改状态() {
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenThrow(new ModelInvocationException(ModelError.UNAVAILABLE, "全挂"));

        assertThrows(ModelInvocationException.class, () -> loop().run(session, ChunkSink.NOOP, NO_CONTEXT));
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
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed())
                .thenReturn(passed());
        when(catalog.agents()).thenReturn(Collections.singletonList(reflectAgent()));
        when(forks.run(any(Session.class), any())).thenReturn("下次先读文件");

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        verify(commitScope, times(1)).stageFact(any(io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection.class));
        verify(forks).run(any(Session.class), org.mockito.ArgumentMatchers.contains("失败原因"));
    }

    /**
     * 反思 fork 期间被终止：取消要上抛（不当成“本次不反思”），且取消之后不得再开新 Attempt / 重划 / 终止。
     * 规则出处：08-application §8.9.4.1（终止不推进）、§8.9.7（fork 期间被终止的语义）。
     */
    @Test
    void 反思期间被终止则上抛且不再推进状态() {
        policy = policy.adjust(new java.util.LinkedHashMap<String, Object>() {{
            put("maxRetries", 3);
            put("reflectionThreshold", 1);
        }});
        Plan plan = plan(VerificationType.DETERMINISTIC);
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("第一次"));
        when(verificationRunner.verify(any(Session.class), any(Plan.class), any(), anyInt(), any(), any()))
                .thenReturn(failed());
        when(catalog.agents()).thenReturn(Collections.singletonList(reflectAgent()));
        when(forks.run(any(Session.class), any()))
                .thenThrow(new java.util.concurrent.CancellationException("用户中断"));

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> loop().run(session, ChunkSink.NOOP, NO_CONTEXT));

        // 失败本身就是取消前已正常落盘的事实（markStepFailed），这里只断言取消之后没有再推进
        assertEquals(1, plan.steps().get(0).attempts().size(), "终止不耗重试：不开新 Attempt");
        assertEquals(0, plan.replanGeneration(), "终止不触发重划");
        assertEquals(PlanStatus.EXECUTING, plan.status(), "终止不把计划判死");
    }

    private static io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition reflectAgent() {
        return io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition
                .builder(AgentId.of("builtin.reflect"), "反思代理",
                        io.github.zcd0831.jellyfish.domain.action.agent.AgentSource.BUILT_IN)
                .basePrompt(new io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate("你是复盘者",
                        java.util.Collections.<String>emptyList()))
                .status(io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus.ACTIVE)
                .build();
    }

    /**
     * “第 x/N 步”的 x 与 N 同一口径：N 只计未被 SUPERSEDED 的步骤。
     * 旧尾部会被重划置为 SUPERSEDED 并保留审计，算进去分母会虚高。
     * 规则出处：08-application §8.7。
     */
    @Test
    void 进度分母不把被重划替换的旧步骤算进去() {
        Plan plan = Plan.create(PlanId.of("p1"), SessionId.of("s1"), "目标", java.util.Arrays.asList(
                PlanSteps.draft(StepId.of("step1"), 1, "第一步", "g", "o", "c",
                        EnumSet.noneOf(VerificationType.class)),
                PlanSteps.draft(StepId.of("step2"), 2, "第二步", "g", "o", "c",
                        EnumSet.noneOf(VerificationType.class))));
        planScript.add(plan);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class))).thenAnswer(call -> {
            PlanStep current = call.getArgument(1);
            if (current == null) {
                return converged();   // 结论交付轮
            }
            if (calls.incrementAndGet() == 1) {
                // 执行第一步时重划：把未执行的尾部（第二步）替掉
                plan.applyReplan(Collections.singletonList(PlanSteps.draft(StepId.of("step3"), 0, "第三步",
                        "g", "o", "c", Collections.<VerificationType>emptySet())));
            }
            return submitted("完成");
        });

        loop().run(session, ChunkSink.NOOP, NO_CONTEXT);

        assertTrue(progressEvents.stream().anyMatch(e -> e.message().equals("第 2/2 步：第三步")),
                "分母只计未 SUPERSEDED 的步骤：" + progressEvents);
    }

    @Test
    void 交付轮禁用全部工具且跑完即结束() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("完成"))
                .thenReturn(converged());

        assertEquals(RunOutcome.CONVERGED, loop().run(session, ChunkSink.NOOP, NO_CONTEXT));

        assertEquals(PlanStatus.COMPLETED, plan.status());
        verify(reActLoop, times(2)).runIteration(any(Session.class), any(), any(ChunkSink.class));
    }

    /**
     * 沉淀期间被终止：取消是终态，必须上抛（不能吞成“本次不沉淀”），也不得再进交付轮。
     * 规则出处：08-application §8.9.4.1、§8.9.7（fork 期间被终止 ⇒ 不写告知记录、不播报）。
     */
    @Test
    void 沉淀期间被终止则上抛且不进交付轮() {
        Plan plan = plan();
        planScript.add(plan);
        when(reActLoop.runIteration(any(Session.class), any(), any(ChunkSink.class)))
                .thenReturn(submitted("做完了"));
        doThrow(new java.util.concurrent.CancellationException("用户中断"))
                .when(curator).curate(any(Session.class), any(DistillationInput.class));

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> loop().run(session, ChunkSink.NOOP, NO_CONTEXT));

        assertEquals(PlanStatus.COMPLETED, plan.status(), "沉淀前计划已完结，这一轮不算失败");
        verify(reActLoop, times(1)).runIteration(any(Session.class), any(), any(ChunkSink.class));
    }
}
