package io.github.zcd0831.jellyfish.application.shared;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
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
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 启动对账：崩溃残留的业务补齐（5 条）。只管业务语义，文件级残留在基础设施层。
 * 规则出处：08-application §8.10（StartupReconciler 的 5 条规则）。
 */
public final class StartupReconciler implements SessionResidueReconciler {

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
     * 启动只做全局那一条（规则 4：归档中间态）。
     * 其余 5 条按会话在**续跑入口**做（{@link #reconcile(Session)} / {@link #reconcileBudget}）：
     * 启动成本若正比于会话总量，用户攒了几千个会话就要等几秒，而绝大多数会话根本不会再被打开。
     * 规则出处：08-application §8.10、§8.9.3（`U3->>SR: reconcile(session)`）、
     * 04-perception §4.8.1 规则 7（续跑先对账）。
     */
    public void reconcileStartup() {
        reconcileArchiveResidue();
    }

    /**
     * 会话要跑之前补齐这一个会话：规则 1/2/3/5/6，外加根会话的台账（规则 5）。
     * 幂等：干净会话一次读、零写入。
     */
    @Override
    public void reconcileBeforeRun(Session session) {
        reconcileSession(session.id());
        if (session.parentSessionId() == null) {
            reconcileBudget(session.id());
        }
    }

    /**
     * 按会话对账。**先看有没有要补的，再去读会话头**：启动成本正比于会话数，
     * 每多读一次 meta.json 都是纯开销，而绝大多数会话是干净的。
     */
    private void reconcileSession(Session session) {
        reconcileSession(session.id());
    }

    private void reconcileSession(SessionId sessionId) {
        for (Plan plan : plans.findRunning(sessionId)) {
            if (reconcilePlan(plan)) {
                flush(plan);
            }
        }
        reconcileHalfToolCall(sessionId);
    }

    /** 规则 4：归档中间态对账——包完整则补删原目录，半截包直接丢弃。 */
    private void reconcileArchiveResidue() {
        sessions.cleanupArchiveResidue();
    }

    /**
     * 规则 ①②⑥：事实已落盘但聚合没跟上。
     *
     * @return 是否真的补了什么（没有就一个字节都不写，续跑只花一次读）
     */
    private boolean reconcilePlan(Plan plan) {
        boolean changed = false;
        for (PlanStep step : plan.steps()) {
            if (step.status() != StepStatus.RUNNING) {
                continue;
            }
            Attempt attempt = step.attempts().isEmpty() ? null : step.attempts().get(step.attempts().size() - 1);
            if (attempt == null) {
                continue;
            }
            changed |= reconcileReflection(plan, step, attempt);
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
                changed = true;
            }
        }
        return convergeIfExhausted(plan) || changed;
    }

    /**
     * 补齐之后必须收敛：规则 1/2 判完最后一批步骤，计划可能停在「EXECUTING 却无当前步」；
     * 不收尾的话该会话再也无法 generate_plan（06-planning §6.8.1「无当前步即终结」）。
     */
    private boolean convergeIfExhausted(Plan plan) {
        if (plan.failIfNoCurrentStep(EXHAUSTED_STEPS)) {
            announce("对账：所有步骤已终结，计划收敛为失败（" + plan.id().getValue() + "）");
            return true;
        }
        return false;
    }

    /**
     * 规则 ⑥：反思事实落了、attempt 还没挂上 ⇒ 按坐标取回并补挂。
     * 反思 id 由 (planId, stepId, attemptNo) 决定，所以不需要额外索引。
     * 规则出处：08-application §8.10 规则 6、§8.8.1。
     */
    private boolean reconcileReflection(Plan plan, PlanStep step, Attempt attempt) {
        if (attempt.reflectionId() != null) {
            return false;
        }
        ReflectionId id = Reflection.identify(plan.id(), step.id(), attempt.attemptNo());
        if (!reflections.get(id).isPresent()) {
            return false;
        }
        plan.recordReflection(step.id(), attempt.attemptNo(), id);
        announce("对账：按既存反思补齐尝试记录（" + step.id().getValue() + " / #" + attempt.attemptNo() + "）");
        return true;
    }

    /**
     * 规则 ③：流水末尾有 TOOL_REQUEST 没有 TOOL_RESULT ⇒ <strong>补一条中止结果</strong>。
     * 崩溃/强杀不形成任何终态：attempt 不落终态、不消耗重试名额，会话可以直接续跑
     * （08-application §8.10 规则 3、§8.9.4.1）。
     */
    private void reconcileHalfToolCall(SessionId sessionId) {
        List<Record> recent = records.last(sessionId, 2);
        if (recent.size() < 1) {
            return;
        }
        Record request = recent.get(recent.size() - 1);
        if (request.role() != RecordRole.TOOL_REQUEST) {
            return;   // 干净的收尾：不读会话头
        }
        Session session = sessions.load(sessionId);
        if (session == null) {
            return;
        }
        // 中止结果必须与原请求同一轮（turnId）：自成一轮会让原 TOOL_REQUEST 仍然悬空
        // 规则出处：04-perception §4.8.1 规则 14；08-application §8.10 规则 3
        Record aborted = session.appendRecord(
                io.github.zcd0831.jellyfish.domain.perception.session.RecordDraft
                        .builder(RecordRole.TOOL_RESULT, ABORTED_TOOL_RESULT)
                        .turnId(request.turnId()).producerModelId(request.producerModelId()).build(),
                System.currentTimeMillis());
        commitScope.stageAppend(sessionId, aborted);
        commitScope.commit();
        announce("对账：半截工具调用补中止结果，会话可续跑");
    }

    /** 规则 ⑤：台账缺失或损坏 ⇒ 重建为 0 并告警（预算不精确可接受）。 */
    public void reconcileBudget(SessionId rootSessionId) {
        try {
            if (budgets.load(rootSessionId) == null) {
                budgets.save(Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootSessionId));
                announce("对账：台账缺失，已重建为 0（" + rootSessionId.getValue() + "）");
            }
        } catch (RuntimeException corrupted) {
            budgets.save(Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootSessionId));
            announce("对账：台账损坏，已重建为 0（" + rootSessionId.getValue() + "）");
        }
    }

    private void flush(Plan plan) {
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
