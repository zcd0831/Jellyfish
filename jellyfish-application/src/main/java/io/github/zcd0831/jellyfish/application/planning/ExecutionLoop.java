package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanCreationException;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外循环：算上下文 → 取 step → 跑一次内循环 → 验证 → 按三条出口推进。
 * 唯一有权推进计划状态机的地方。规则出处：模块-应用层 §8.1、模型图 §5.1、模块-规划 §8.1。
 */
public final class ExecutionLoop {

    public enum Outcome {
        CONVERGED,
        SUSPENDED,
        ROUNDS_EXHAUSTED
    }

    private final ContextWindowService contextWindow;
    private final ReActLoop reActLoop;
    private final VerificationRunner verificationRunner;
    private final PlanRepository plans;
    private final PlanFactory planFactory;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;
    private final ModelInvocationPort model;
    private final BudgetService budgetService;

    public ExecutionLoop(ContextWindowService contextWindow, ReActLoop reActLoop,
                         VerificationRunner verificationRunner, PlanRepository plans, PlanFactory planFactory,
                         CatalogRepository catalog, PolicyRepository policies, CommitScope commitScope,
                         DomainEventPublisher eventPublisher, ProgressPort progress, ModelInvocationPort model,
                         BudgetService budgetService) {
        this.contextWindow = contextWindow;
        this.reActLoop = reActLoop;
        this.verificationRunner = verificationRunner;
        this.plans = plans;
        this.planFactory = planFactory;
        this.catalog = catalog;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
        this.model = model;
        this.budgetService = budgetService;
    }

    public Outcome run(Session session, ChunkSink sink) {
        RuntimePolicy policy = policies.load();
        Plan plan = runningPlan(session);
        boolean collapsedOnce = false;

        while (true) {
            contextWindow.ensureWithinLimit(session);
            PlanStep step = plan != null && plan.hasExecutablePlan() ? plan.getCurrentStep() : null;
            Attempt attempt = null;
            if (step != null && step.status() == StepStatus.PENDING) {
                attempt = plan.beginCurrentStep();
                commitPlan(plan);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_STARTED,
                        "第 " + step.sequence() + " 步：" + step.description()));
            } else if (step != null && !step.attempts().isEmpty()) {
                attempt = step.attempts().get(step.attempts().size() - 1);
            }

            IterationResult iteration;
            try {
                iteration = reActLoop.runIteration(session, step, sink);
            } catch (ModelInvocationException failure) {
                if (failure.error() == ModelError.CONTEXT_EXCEEDED && !collapsedOnce) {
                    // 请求超限：强制折叠一次后重试，不消耗重试名额（模块-应用层 §9）
                    contextWindow.forceCollapse(session);
                    collapsedOnce = true;
                    continue;
                }
                throw failure;   // 模型不可用：不改任何状态
            }

            if (!iteration.isConverged()) {
                if (step == null) {
                    return Outcome.ROUNDS_EXHAUSTED;
                }
                plan.markAttemptFailed(step.id(), iteration.attemptNo());
                commitPlan(plan);
                if (advanceAfterFailure(session, plan, step, "撞 maxReActRounds")) {
                    return Outcome.CONVERGED;
                }
                continue;
            }

            if (step == null) {
                // generate_plan 成功不打断内循环：本轮收敛后下一轮转入计划执行（模型图 §5.1）
                Plan created = runningPlan(session);
                if (created != null) {
                    plan = created;
                    continue;
                }
                return Outcome.CONVERGED;
            }
            if (!iteration.submitted()) {
                return Outcome.SUSPENDED;   // 挂起：attempt 不落终态，等用户下次输入
            }

            if (!stepNeedsVerification(step)) {
                plan.markStepSucceeded(step.id(), iteration.submittedSummary(), null);
                commitPlan(plan);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                        "第 " + step.sequence() + " 步完成（免验证）"));
            } else {
                VerificationReport report = verificationRunner.verify(session, plan, step,
                        attempt == null ? 0 : attempt.attemptNo(), iteration.submittedSummary());
                commitScope.stageFact(report);
                if (report.outcome() == VerificationOutcome.PASSED) {
                    plan.markStepSucceeded(step.id(), iteration.submittedSummary(), report.id());
                    commitPlan(plan);
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + step.sequence() + " 步验收通过"));
                } else {
                    plan.markStepFailed(step.id(), report.id());
                    commitPlan(plan);
                    publish(report.pullEvents());
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + step.sequence() + " 步验收未通过：" + report.outcome()));
                    if (advanceAfterFailure(session, plan, step,
                            report.failureReason() == null ? String.valueOf(report.outcome())
                                    : report.failureReason())) {
                        return Outcome.CONVERGED;
                    }
                    continue;
                }
            }

            if (plan.status() == PlanStatus.COMPLETED) {
                // 计划完结后再跑一轮自由内循环，由模型产出结论（模型图 §5.1）
                publish(plan.pullEvents());
                plan = null;
            }
        }
    }

    /**
     * 失败后的推进：先重试，重试次数用尽则重划，重划次数用尽则终止。
     * 「还能不能重试 / 该不该重划」由这里判定，聚合只执行迁移（§7.1 原则）。
     */
    private boolean advanceAfterFailure(Session session, Plan plan, PlanStep step, String reason) {
        RuntimePolicy policy = policies.load();
        if (failedAttempts(step) < policy.maxRetries()) {
            plan.retryStep(step.id());
            commitPlan(plan);
            return false;
        }
        if (plan.replanGeneration() < policy.maxReplanGeneration()) {
            if (replan(session, plan, step, reason)) {
                return false;
            }
        }
        plan.terminate("重划用尽：" + reason);
        commitPlan(plan);
        publish(plan.pullEvents());
        return true;
    }

    /** 重划：让模型给出新的剩余步骤，聚合只替换未执行尾部。 */
    private boolean replan(Session session, Plan plan, PlanStep failed, String reason) {
        RuntimePolicy policy = policies.load();
        String instruction = "原计划在第 " + failed.sequence() + " 步失败：" + reason
                + "。请给出修正后的剩余步骤，仍用 steps 数组表达，不要重复已完成的步骤。";
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(plan.sessionId() == null
                    ? session.activeModelId() : session.activeModelId(), instruction,
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, "目标：" + plan.goal())),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage().total());
            }
            Map<String, Object> args = Json.parseObject(result.text());
            Plan draft = planFactory.create(PlanId.of(UUID.randomUUID().toString()), session.id(), args,
                    boundTools(session));
            plan.applyReplan(draft.steps());
            commitPlan(plan);
            publish(Collections.<DomainEvent>emptyList());
            return true;
        } catch (RuntimeException cannotReplan) {
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.RETRY_SCHEDULED, "重划失败：" + cannotReplan.getMessage()));
            return false;
        }
    }

    private static boolean stepNeedsVerification(PlanStep step) {
        return !step.requiredVerifications().isEmpty();
    }

    private static int failedAttempts(PlanStep step) {
        int failed = 0;
        for (Attempt attempt : step.attempts()) {
            if (attempt.outcome() == AttemptOutcome.FAILED) {
                failed++;
            }
        }
        return failed;
    }

    private Plan runningPlan(Session session) {
        List<Plan> running = plans.findRunning(session.id());
        if (running.isEmpty()) {
            return null;
        }
        // 同一会话同时只有一个可执行计划（§7.1 规则 2 的会话侧投影）
        Plan plan = running.get(0);
        return plan.status() == PlanStatus.EXECUTING ? plan : null;
    }

    private java.util.Set<ToolId> boundTools(Session session) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(session.agentId())) {
                return new java.util.LinkedHashSet<ToolId>(agent.listBoundToolIds());
            }
        }
        return Collections.emptySet();
    }

    private void commitPlan(Plan plan) {
        commitScope.stageReplace(plan);
        commitScope.commit();
        publish(plan.pullEvents());
    }

    private void publish(List<DomainEvent> events) {
        if (!events.isEmpty()) {
            eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(events));
        }
    }
}
