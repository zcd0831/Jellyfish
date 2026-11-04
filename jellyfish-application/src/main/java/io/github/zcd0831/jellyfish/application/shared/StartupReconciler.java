package io.github.zcd0831.jellyfish.application.shared;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 启动对账：崩溃残留的业务补齐（5 条）。只管业务语义，文件级残留在基础设施层。
 * 规则出处：08-application §8.10（StartupReconciler 的 5 条规则）。
 */
public final class StartupReconciler {

    private final SessionRepository sessions;
    private final PlanRepository plans;
    private final VerificationReportRepository reports;
    private final RecordStore records;
    private final BudgetRepository budgets;
    private final ReflectionRepository reflections;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;

    /** 与内循环同一句中止事实：终止/崩溃都不算工具失败，只是没拿到结果。 */
    private static final String EXHAUSTED_STEPS = "对账后所有步骤已终结";

    private static final String ABORTED_TOOL_RESULT =
            "（本轮中止：进程退出，工具结果未回灌）";

    public StartupReconciler(SessionRepository sessions, PlanRepository plans,
                             VerificationReportRepository reports, RecordStore records,
                             BudgetRepository budgets, ReflectionRepository reflections,
                             CommitScope commitScope, DomainEventPublisher eventPublisher, ProgressPort progress) {
        this.sessions = sessions;
        this.plans = plans;
        this.reports = reports;
        this.records = records;
        this.budgets = budgets;
        this.reflections = reflections;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
    }

    /**
     * 启动对账的编排入口：规则 4 是全局的（扫整个 archive 区），只做一次；
     * 规则 1/2/3 按会话补齐，规则 5 只对根会话。5 条全部幂等。
     * 规则出处：08-application §8.10（崩溃残留 ⇒ 启动时补齐）。
     */
    public void reconcileStartup() {
        reconcileArchiveResidue();
        for (SessionSummary summary : sessions.list()) {
            Session session = sessions.load(summary.id());
            if (session == null) {
                continue;
            }
            reconcileSession(session);
            if (!summary.isSubSession()) {
                reconcileBudget(session);
            }
        }
    }

    /** 按会话对账（规则 1/2/3）；规则 4 是全局的，由 {@link #reconcileStartup()} 统一做一次。 */
    public void reconcile(Session session) {
        reconcileSession(session);
    }

    private void reconcileSession(Session session) {
        for (Plan plan : plans.findRunning(session.id())) {
            reconcilePlan(session, plan);
        }
        reconcileHalfToolCall(session);
    }

    /** 规则 4：归档中间态对账——包完整则补删原目录，半截包直接丢弃。 */
    private void reconcileArchiveResidue() {
        sessions.cleanupArchiveResidue();
    }

    /** 规则 ①②⑥：事实已落盘但聚合没跟上。 */
    private void reconcilePlan(Session session, Plan plan) {
        for (PlanStep step : plan.steps()) {
            if (step.status() != StepStatus.RUNNING) {
                continue;
            }
            Attempt attempt = step.attempts().isEmpty() ? null : step.attempts().get(step.attempts().size() - 1);
            if (attempt == null) {
                continue;
            }
            reconcileReflection(plan, step, attempt);
            List<VerificationReport> found = reports.findByStep(plan.id(), step.id());
            for (VerificationReport report : found) {
                if (report.attemptNo() != attempt.attemptNo()) {
                    continue;
                }
                if (report.outcome() == VerificationOutcome.PASSED) {
                    plan.markStepSucceeded(step.id(), step.finalOutput(), report.id());
                    announce("对账：按既存 PASSED 报告补齐步骤完成（" + step.id().getValue() + "）");
                } else {
                    plan.markStepFailed(step.id(), report.id());
                    announce("对账：按既存 " + report.outcome() + " 报告补齐步骤失败（" + step.id().getValue() + "）");
                }
            }
        }
        convergeIfExhausted(plan);
        flush(session, plan);
    }

    /**
     * 补齐之后必须收敛：规则 1/2 判完最后一批步骤，计划可能停在「EXECUTING 却无当前步」；
     * 不收尾的话该会话再也无法 generate_plan（06-planning §6.8.1「无当前步即终结」）。
     */
    private void convergeIfExhausted(Plan plan) {
        if (plan.failIfNoCurrentStep(EXHAUSTED_STEPS)) {
            announce("对账：所有步骤已终结，计划收敛为失败（" + plan.id().getValue() + "）");
        }
    }

    /**
     * 规则 ⑥：反思事实落了、attempt 还没挂上 ⇒ 按坐标取回并补挂。
     * 反思 id 由 (planId, stepId, attemptNo) 决定，所以不需要额外索引。
     * 规则出处：08-application §8.10 规则 6、§8.8.1。
     */
    private void reconcileReflection(Plan plan, PlanStep step, Attempt attempt) {
        if (attempt.reflectionId() != null) {
            return;
        }
        ReflectionId id = Reflection.identify(plan.id(), step.id(), attempt.attemptNo());
        if (!reflections.get(id).isPresent()) {
            return;
        }
        plan.recordReflection(step.id(), attempt.attemptNo(), id);
        announce("对账：按既存反思补齐尝试记录（" + step.id().getValue() + " / #" + attempt.attemptNo() + "）");
    }

    /**
     * 规则 ③：流水末尾有 TOOL_REQUEST 没有 TOOL_RESULT ⇒ <strong>补一条中止结果</strong>。
     * 崩溃/强杀不形成任何终态：attempt 不落终态、不消耗重试名额，会话可以直接续跑
     * （08-application §8.10 规则 3、§8.9.4.1）。
     */
    private void reconcileHalfToolCall(Session session) {
        List<Record> recent = records.last(session.id(), 2);
        if (recent.size() < 1) {
            return;
        }
        Record request = recent.get(recent.size() - 1);
        if (request.role() != RecordRole.TOOL_REQUEST) {
            return;
        }
        // 中止结果必须与原请求同一轮（turnId）：自成一轮会让原 TOOL_REQUEST 仍然悬空
        // 规则出处：04-perception §4.8.1 规则 14；08-application §8.10 规则 3
        Record aborted = session.appendRecord(RecordRole.TOOL_RESULT, ABORTED_TOOL_RESULT, null,
                request.turnId(), request.producerModelId(), null, System.currentTimeMillis());
        commitScope.stageAppend(session.id(), aborted);
        commitScope.commit();
        announce("对账：半截工具调用补中止结果，会话可续跑");
    }

    /** 规则 ⑤：台账缺失或损坏 ⇒ 重建为 0 并告警（预算不精确可接受）。 */
    public void reconcileBudget(Session rootSession) {
        try {
            if (budgets.load(rootSession.id()) == null) {
                budgets.save(Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootSession.id()));
                announce("对账：台账缺失，已重建为 0（" + rootSession.id().getValue() + "）");
            }
        } catch (RuntimeException corrupted) {
            budgets.save(Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootSession.id()));
            announce("对账：台账损坏，已重建为 0（" + rootSession.id().getValue() + "）");
        }
    }

    private void flush(Session session, Plan plan) {
        commitScope.stageReplace(plan);
        commitScope.commit();
        publish(plan.pullEvents());
    }

    private void publish(List<DomainEvent> events) {
        if (!events.isEmpty()) {
            eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(events));
        }
    }

    private void announce(String message) {
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED, message));
    }
}
