package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.domain.perception.session.RecordDraft;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionHeader;
import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.StartupReconciler;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;
import io.github.zcd0831.jellyfish.infrastructure.event.InProcessEventPublisher;
import io.github.zcd0831.jellyfish.infrastructure.file.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动对账 5 条规则中的 1/2/3/5。规则出处：08-application §8.10。
 */
class S4ReconcilerTest {

    @TempDir
    Path tmp;

    private JsonCodec codec;
    private DataDirectory dir;
    private SessionRepository sessions;
    private PlanRepository plans;
    private VerificationReportRepository reports;
    private FileRecordStore records;
    private BudgetRepository budgets;
    private ReflectionRepository reflections;
    private final List<ProgressEvent> announced = new ArrayList<ProgressEvent>();
    private StartupReconciler reconciler;
    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        dir = DataDirectory.of(tmp.toString());
        codec = new JsonCodec();
        sessions = new FileSessionRepository(dir, codec);
        plans = new FilePlanRepository(dir, codec);
        reports = new FileReportRepository(dir, codec);
        records = new FileRecordStore(dir, codec);
        budgets = new FileBudgetRepository(dir, codec);
        reflections = new FileReflectionRepository(dir, codec);
        Session session = Session.create(SessionHeader
                .builder(SessionId.of("s1"), AgentId.of("builtin.main"), ModelId.of("m1")).createdDate(1L).build());
        sessions.save(session);
        sessionId = session.id();
        reconciler = new StartupReconciler(sessions, plans, reports, records, budgets, reflections,
                new FileCommitScope(dir, codec), new InProcessEventPublisher(),
                event -> announced.add(event));
    }

    private Plan planWithRunningStep() {
        Plan plan = Plan.create(PlanId.of("p1"), sessionId, "目标", Collections.singletonList(
                PlanSteps.draft(StepId.of("step1"), 1, "做事", "目标", "期望", "标准",
                        EnumSet.of(VerificationType.DETERMINISTIC))));
        plan.beginCurrentStep();
        plans.save(plan);
        return plan;
    }

    @Test
    void 规则1_有PASSED报告就补齐步骤完成() {
        planWithRunningStep();
        reports.save(VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("step1"), 1, VerificationOutcome.PASSED, null, null, 10L));

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        Plan reloaded = plans.findRunning(sessionId).isEmpty() ? plans.load(PlanId.of("p1")) : plans.findRunning(sessionId).get(0);
        assertEquals(StepStatus.SUCCEEDED, reloaded.steps().get(0).status());
    }

    @Test
    void 规则2_有FAILED报告就补齐步骤失败() {
        planWithRunningStep();
        reports.save(VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("step1"), 1, VerificationOutcome.FAILED, VerificationType.LLM_JUDGE, "不合格", 10L));

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        Plan reloaded = plans.load(PlanId.of("p1"));
        assertEquals(StepStatus.FAILED, reloaded.steps().get(0).status());
    }

    /**
     * 补判的是最后一个未完成步：计划会停在「EXECUTING 却无当前步」，
     * 不收敛的话 generate_plan 永久拒绝，会话再也无法规划。
     * 规则出处：06-planning §6.8.1「无当前步即终结」、08-application §8.10。
     */
    @Test
    void 规则2补齐后计划收敛为失败_会话可重新规划() {
        planWithRunningStep();
        reports.save(VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("step1"), 1, VerificationOutcome.FAILED, VerificationType.LLM_JUDGE, "不合格", 10L));

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        assertTrue(plans.findRunning(sessionId).isEmpty(), "收敛后不该再有执行中的计划");
        Plan reloaded = plans.load(PlanId.of("p1"));
        assertEquals(PlanStatus.FAILED, reloaded.status());
        assertEquals("对账后所有步骤已终结", reloaded.terminationReason());
        assertTrue(announced.stream().anyMatch(e -> e.message().contains("计划收敛为失败")),
                "收敛要让用户看见：" + announced);
    }

    /**
     * 崩在「反思事实落盘」与「挂回 attempt」之间：反思文件在、reflectionId 是 null。
     * 反思 id 由 (planId, stepId, attemptNo) 决定，对账按坐标取回补挂。
     * 规则出处：08-application §8.10 规则 6、§8.8.1。
     */
    /**
     * 崩溃残留的端到端口径：这个会话要跑之前先补齐，用户 `/open` 再说一句话就能续跑。
     * 规则出处：04-perception §4.8.1 规则 7、08-application §8.9.3、§8.10 规则 3。
     */
    @Test
    void 崩溃残留经续跑前对账后会话可续跑() {
        Session crashed = sessions.load(sessionId);
        records.appendAll(sessionId, java.util.Arrays.asList(
                crashed.appendRecord(RecordDraft.builder(RecordRole.USER, "读一下文件").turnId("turn-1").build(), 1L),
                crashed.appendRecord(RecordDraft
                        .builder(RecordRole.ASSISTANT, "我来读").turnId("turn-2").producerModelId("m1").build(), 2L),
                crashed.appendRecord(RecordDraft
                        .builder(RecordRole.TOOL_REQUEST, "{\"tool\":\"read_file\"}")
                        .turnId("turn-2").producerModelId("m1").build(), 3L)));

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        List<Record> tail = records.last(sessionId, 1);
        assertEquals(RecordRole.TOOL_RESULT, tail.get(0).role());
        assertEquals("turn-2", tail.get(0).turnId(), "中止结果挂在原轮上，流水才算闭合");
        // 续跑的第一步：会话带着流水重建，组装上下文不抛；且那条轮仍然完整（ASSISTANT 打头）
        Session resumed = Session.rehydrate(SessionHeader
                .builder(sessionId, AgentId
                .of("builtin.main"), ModelId.of("m1")).createdDate(1L).build(), records.last(sessionId, 10));
        assertTrue(resumed.buildContextForLlm(text -> text == null ? 0 : text.length(), 100000)
                .getRecords().size() >= 4);
    }

    @Test
    void 规则6_有反思事实就补挂到尝试上() {
        planWithRunningStep();
        ReflectionId reflectionId = Reflection.identify(PlanId.of("p1"), StepId.of("step1"), 1);
        reflections.save(Reflection.record(PlanId.of("p1"), StepId.of("step1"), 1, "下次先读文件", 10L));

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        Plan reloaded = plans.load(PlanId.of("p1"));
        Attempt attempt = reloaded.steps().get(0).attempts().get(0);
        assertEquals(reflectionId, attempt.reflectionId(), "反思事实在，attempt 必须挂上它");
        assertTrue(announced.stream().anyMatch(e -> e.message().contains("反思")), announced.toString());
    }

    @Test
    void 规则6_没有反思事实就不动尝试() {
        planWithRunningStep();

        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        Plan reloaded = plans.load(PlanId.of("p1"));
        assertNull(reloaded.steps().get(0).attempts().get(0).reflectionId());
    }

    /**
     * 崩溃/强杀不形成任何终态：补一条中止结果让流水可续跑，attempt 不落终态、不耗重试。
     * 规则出处：08-application §8.10 规则 3、§8.9.4.1。
     */
    @Test
    void 规则3_半截工具调用补中止结果且attempt不结束() {
        planWithRunningStep();
        Session session = sessions.load(sessionId);
        Record request = session.appendRecord(RecordDraft
                .builder(RecordRole.TOOL_REQUEST, "{}").turnId("turn-7").producerModelId("m1").build(), 5L);
        records.appendAll(sessionId, java.util.Collections.singletonList(request));

        reconciler.reconcileBeforeRun(session);

        Plan reloaded = plans.load(PlanId.of("p1"));
        assertEquals(StepStatus.RUNNING, reloaded.steps().get(0).status(), "崩溃不算失败，步骤仍在跑");
        assertNull(reloaded.steps().get(0).attempts().get(0).outcome(), "attempt 不落终态");
        List<Record> tail = records.last(sessionId, 2);
        Record aborted = tail.get(tail.size() - 1);
        assertEquals(RecordRole.TOOL_RESULT, aborted.role(), "半截调用要补中止结果");
        // 补的中止结果必须挂在原请求那一轮，否则原 TOOL_REQUEST 仍自成一"轮"
        assertEquals("turn-7", aborted.turnId(), "中止结果与原请求同一轮");
        assertTrue(announced.stream().anyMatch(e -> e.message().contains("半截工具调用")));
        // 补齐的唯一验收口径：这一轮能重新组装上下文（否则续跑第一句就抛"流水末尾有未闭合的工具请求"）
        sessions.load(sessionId).buildContextForLlm(text -> text == null ? 0 : text.length(), 100000);
    }

    /**
     * 启动只清全局残留（规则 4），**不遍历会话**：启动成本不能正比于用户攒下的会话总量。
     * 会话级规则 1/2/3/5 在它自己要跑之前做（08-application §8.10、§8.9.3）。
     */
    @Test
    void 启动不遍历会话_会话级补齐留到续跑前() {
        Session first = sessions.load(sessionId);
        records.appendAll(sessionId, java.util.Collections.singletonList(
                first.appendRecord(RecordDraft
                        .builder(RecordRole.TOOL_REQUEST, "{}").turnId("t1").producerModelId("m1").build(), 5L)));
        Session second = Session.create(SessionHeader
                .builder(SessionId.of("s2"), AgentId.of("builtin.main"), ModelId.of("m1")).createdDate(2L).build());
        sessions.save(second);
        records.appendAll(second.id(), java.util.Collections.singletonList(
                second.appendRecord(RecordDraft
                        .builder(RecordRole.TOOL_REQUEST, "{}").turnId("t2").producerModelId("m1").build(), 6L)));

        reconciler.reconcileStartup();

        assertEquals(RecordRole.TOOL_REQUEST, records.last(sessionId, 1).get(0).role(),
                "启动不该动流水：会话级的补齐留到它真的要被跑之前");

        // 真正要跑的时候，那一个会话补齐；另一个会话保持原样，谁跑谁补
        reconciler.reconcileBeforeRun(sessions.load(sessionId));

        assertEquals(RecordRole.TOOL_RESULT, records.last(sessionId, 1).get(0).role(), "跑之前补齐");
        assertEquals(RecordRole.TOOL_REQUEST, records.last(SessionId.of("s2"), 1).get(0).role(),
                "没被跑的会话不补——成本正比于用户实际做的事");
        assertEquals(0L, budgets.load(sessionId).consumedTokens(), "规则 5 只对根会话，跑之前顺带做");
    }

    @Test
    void 规则5_台账缺失就重建为0并告警() {
        Session root = sessions.load(sessionId);

        reconciler.reconcileBudget(root.id());

        Budget rebuilt = budgets.load(sessionId);
        assertEquals(0L, rebuilt.consumedTokens());
        assertTrue(announced.stream().anyMatch(e -> e.message().contains("台账缺失")));
    }

    @Test
    void 规则5_台账损坏也重建() {
        Session root = sessions.load(sessionId);
        budgets.save(io.github.zcd0831.jellyfish.domain.action.budget.Budget.openFor(
                BudgetId.of("b1"), sessionId));
        // 写坏文件
        try {
            java.nio.file.Files.write(tmp.resolve("sessions/s1/budget.json"),
                    "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        reconciler.reconcileBudget(root.id());

        assertEquals(0L, budgets.load(sessionId).consumedTokens());
    }
}
