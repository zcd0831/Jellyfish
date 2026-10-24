package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.StartupReconciler;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanSteps;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动对账 5 条规则中的 1/2/3/5。规则出处：模块-应用层 §9。
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
        ReflectionRepository reflections = new FileReflectionRepository(dir, codec);
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1L, null);
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

        reconciler.reconcile(sessions.load(sessionId));

        Plan reloaded = plans.findRunning(sessionId).isEmpty() ? plans.load(PlanId.of("p1")) : plans.findRunning(sessionId).get(0);
        assertEquals(StepStatus.SUCCEEDED, reloaded.steps().get(0).status());
    }

    @Test
    void 规则2_有FAILED报告就补齐步骤失败() {
        planWithRunningStep();
        reports.save(VerificationReport.decide(VerificationReportId.of("r1"), PlanId.of("p1"),
                StepId.of("step1"), 1, VerificationOutcome.FAILED, VerificationType.LLM_JUDGE, "不合格", 10L));

        reconciler.reconcile(sessions.load(sessionId));

        Plan reloaded = plans.load(PlanId.of("p1"));
        assertEquals(StepStatus.FAILED, reloaded.steps().get(0).status());
    }

    @Test
    void 规则3_半截工具调用判attempt失败() {
        planWithRunningStep();
        Session session = sessions.load(sessionId);
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, "{}", null, 5L);
        records.append(sessionId, request);

        reconciler.reconcile(session);

        Plan reloaded = plans.load(PlanId.of("p1"));
        assertEquals(StepStatus.FAILED, reloaded.steps().get(0).status());
        assertTrue(announced.stream().anyMatch(e -> e.message().contains("半截工具调用")));
    }

    @Test
    void 规则5_台账缺失就重建为0并告警() {
        Session root = sessions.load(sessionId);

        reconciler.reconcileBudget(root);

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

        reconciler.reconcileBudget(root);

        assertEquals(0L, budgets.load(sessionId).consumedTokens());
    }
}
