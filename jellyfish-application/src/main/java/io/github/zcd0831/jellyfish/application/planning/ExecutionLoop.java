package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.memory.DistillationInput;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.FeedbackInput;
import io.github.zcd0831.jellyfish.application.memory.RetrievalInput;
import io.github.zcd0831.jellyfish.application.memory.StepBrief;
import io.github.zcd0831.jellyfish.application.memory.StepTrace;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanCreationException;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.RecordRange;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
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
public final class ExecutionLoop implements SessionRunner {

    private static final AgentId REFLECT_AGENT = AgentId.of("builtin.reflect");

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
    private final ExperienceRetriever experienceRetriever;
    private final ExperienceFeedbackService experienceFeedback;
    private final PlanCompletionCurator planCompletionCurator;
    private final ReflectionRepository reflections;
    private final io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository experiences;

    public ExecutionLoop(ContextWindowService contextWindow, ReActLoop reActLoop,
                         VerificationRunner verificationRunner, PlanRepository plans, PlanFactory planFactory,
                         CatalogRepository catalog, PolicyRepository policies, CommitScope commitScope,
                         DomainEventPublisher eventPublisher, ProgressPort progress, ModelInvocationPort model,
                         BudgetService budgetService, ExperienceRetriever experienceRetriever,
                         ExperienceFeedbackService experienceFeedback,
                         PlanCompletionCurator planCompletionCurator,
                         ReflectionRepository reflections,
                         io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository experiences) {
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
        this.experienceRetriever = experienceRetriever;
        this.experienceFeedback = experienceFeedback;
        this.planCompletionCurator = planCompletionCurator;
        this.reflections = reflections;
        this.experiences = experiences;
    }

    /**
     * `context` 只服务外部实现：默认循环的依赖由构造器注入，因此这里不读它
     * （模块-规划 §4：`RunContext` 是给插件实现的扶手）。
     */
    @Override
    public RunOutcome run(Session session, ChunkSink sink, CancellationToken token, RunContext context) {
        RuntimePolicy policy = policies.load();
        Plan plan = runningPlan(session);
        boolean collapsedOnce = false;

        while (true) {
            // 每一轮开始先看取消：流式/工具刚被打断时，不再进入下一轮（§8.4.1）
            token.throwIfCancelled();
            contextWindow.ensureWithinLimit(session);
            PlanStep step = plan != null && plan.hasExecutablePlan() ? plan.getCurrentStep() : null;
            Attempt attempt = null;
            if (step != null && step.status() == StepStatus.PENDING) {
                injectExperiences(session, step);
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

            recordSkillUsage(plan, step, attempt, iteration);

            if (!iteration.isConverged()) {
                if (step == null) {
                    return RunOutcome.ROUNDS_EXHAUSTED;
                }
                if (attempt == null) {
                    throw new DomainInvariantViolation("撞轮次上限时没有可判失败的 attempt");
                }
                // 判的是"当前这个 attempt"；iteration.round 是内循环轮次，两者不是一回事
                plan.markAttemptFailed(step.id(), attempt.attemptNo());
                commitPlan(plan);
                if (advanceAfterFailure(session, plan, step, "撞 maxReActRounds")) {
                    return RunOutcome.CONVERGED;
                }
                continue;
            }

            if (step == null) {
                // generate_plan 成功不打断内循环：本轮收敛后下一轮转入计划执行（模型图 §5.1）
                Plan created = runningPlan(session);
                if (created != null && created.getCurrentStep() != null) {
                    plan = created;
                    assignExperiences(session, created);
                    continue;
                }
                if (created != null) {
                    // 计划还在执行态却没有任何可执行步骤：没有可推进的东西，按收敛退出，
                    // 避免"step 为空 → 又捡回同一个计划"的死循环
                    return RunOutcome.CONVERGED;
                }
                return RunOutcome.CONVERGED;
            }
            if (!iteration.submitted()) {
                return RunOutcome.SUSPENDED;   // 挂起：attempt 不落终态，等用户下次输入
            }

            if (!stepNeedsVerification(step)) {
                plan.markStepSucceeded(step.id(), iteration.submittedSummary(), null);
                commitPlan(plan);
                feedbackSucceeded(step);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                        "第 " + step.sequence() + " 步完成（免验证）"));
            } else {
                VerificationReport report = verificationRunner.verify(session, plan, step,
                        attempt == null ? 0 : attempt.attemptNo(), iteration.submittedSummary());
                commitScope.stageFact(report);
                if (report.outcome() == VerificationOutcome.PASSED) {
                    plan.markStepSucceeded(step.id(), iteration.submittedSummary(), report.id());
                    commitPlan(plan);
                    feedbackSucceeded(step);
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + step.sequence() + " 步验收通过"));
                } else {
                    plan.markStepFailed(step.id(), report.id());
                    commitPlan(plan);
                    publish(report.pullEvents());
                    feedbackFailed(step);
                    reflectIfNeeded(session, plan, step, report.failureReason());
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + step.sequence() + " 步验收未通过：" + report.outcome()));
                    if (advanceAfterFailure(session, plan, step,
                            report.failureReason() == null ? String.valueOf(report.outcome())
                                    : report.failureReason())) {
                        return RunOutcome.CONVERGED;
                    }
                    continue;
                }
            }

            if (plan.status() == PlanStatus.COMPLETED) {
                // 计划完结后再跑一轮自由内循环，由模型产出结论（模型图 §5.1）
                publish(plan.pullEvents());
                curate(session, plan);
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
        curate(session, plan);
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

    /** 计划诞生 / 重划：检索经验并交给 Plan 自己改状态（记忆只返回分配结果）。 */
    private void assignExperiences(Session session, Plan plan) {
        List<StepBrief> briefs = new ArrayList<StepBrief>();
        for (PlanStep step : plan.steps()) {
            if (step.status() == StepStatus.PENDING) {
                briefs.add(new StepBrief(step.id(), step.description()));
            }
        }
        if (briefs.isEmpty()) {
            return;
        }
        Map<StepId, List<ExperienceId>> assignment = experienceRetriever.retrieveAndAssign(session,
                new RetrievalInput(plan.id(), briefs));
        for (Map.Entry<StepId, List<ExperienceId>> entry : assignment.entrySet()) {
            plan.assignExperiences(entry.getKey(), entry.getValue());
        }
        commitPlan(plan);
    }

    /**
     * 步骤启动前把该步分配到的经验注入上下文（一条 SYSTEM 记录，首行带 step 标记用于去重）。
     * 规则出处：模块-感知 §7.1 规则 9 ①（经验注入）、规则 10（不参与淘汰）、§7.3 规则 ③、M24。
     */
    private void injectExperiences(Session session, PlanStep step) {
        List<ExperienceId> ids = step.assignedExperienceIds();
        if (ids.isEmpty() || alreadyInjected(session, step)) {
            return;
        }
        StringBuilder text = new StringBuilder("[经验注入 step=").append(step.id().getValue()).append(']');
        int limit = policies.load().toolResultTruncateLength();
        for (ExperienceId id : ids) {
            java.util.Optional<io.github.zcd0831.jellyfish.domain.memory.experience.Experience> found =
                    experiences.find(id);
            if (!found.isPresent() || !found.get().isActive()) {
                continue;
            }
            io.github.zcd0831.jellyfish.domain.memory.experience.Experience experience = found.get();
            text.append("\n- 适用场景：").append(experience.applicableScenario())
                    .append("｜教训：").append(experience.lesson().getInstruction())
                    .append("｜期望：").append(experience.lesson().getExpectedOutcome());
            if (text.length() > limit) {
                text.append("\n（已截断）");
                break;
            }
        }
        if (text.indexOf("\n") < 0) {
            return;   // 一条可用经验都没有，不写空记录
        }
        Record injected = session.appendRecord(RecordRole.SYSTEM, text.toString(), null,
                System.currentTimeMillis());
        commitScope.stageAppend(session.id(), injected);
        commitScope.commit();
    }

    /** 去重靠流水本身：标记是"可推导状态"，不落库（§7.1 规则 11、M24）。 */
    private static boolean alreadyInjected(Session session, PlanStep step) {
        String marker = "[经验注入 step=" + step.id().getValue() + "]";
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM && record.content() != null
                    && record.content().startsWith(marker)) {
                return true;
            }
        }
        return false;
    }

    private void feedbackSucceeded(PlanStep step) {
        experienceFeedback.onStepSucceeded(feedbackOf(step));
    }

    private void feedbackFailed(PlanStep step) {
        experienceFeedback.onStepFailed(feedbackOf(step));
    }

    /** 本期"注入"与"建议"同源：分配即注入（奖惩不对称仍按两段分支执行）。 */
    private static FeedbackInput feedbackOf(PlanStep step) {
        return new FeedbackInput(step.id(), step.assignedExperienceIds());
    }

    /**
     * 达反思阈值时跑内置反思子代理（按文档是子代理，不是进程内调用），结论落 Reflection 并回注。
     * 规则出处：模块-记忆 §7.2、§8.1 回注。
     */
    private void reflectIfNeeded(Session session, Plan plan, PlanStep step, String failureReason) {
        int failed = failedAttempts(step);
        if (!policies.load().needsReflection(failed)) {
            return;
        }
        String conclusion = reflect(session, step, failureReason);
        if (conclusion == null) {
            return;
        }
        Reflection reflection = Reflection.record(plan.id(), step.id(), failedIn(step), conclusion,
                System.currentTimeMillis());
        commitScope.stageFact(reflection);
        plan.recordReflection(step.id(), reflection.attemptNo(), reflection.id());
        commitScope.stageReplace(plan);
        commitScope.commit();
        publish(reflection.pullEvents());
    }

    /**
     * 反思是"一段文本进、一段结论出"，直连端口即可（与检索、沉淀、裁判一致）。
     * 代理缺失或调用失败 ⇒ 本次不反思：把错误文案存成反思结论比不反思更糟。
     */
    private String reflect(Session session, PlanStep step, String failureReason) {
        AgentDefinition agent = agentOf(REFLECT_AGENT);
        if (agent == null) {
            return null;
        }
        String task = "原定目标：" + step.description() + "\n失败原因：" + failureReason
                + "\n请给出下一次该改什么。";
        try {
            ModelInvocationResult result = model.invoke(new ModelInvocationRequest(session.activeModelId(),
                    agent.basePrompt().getTemplateText(),
                    Collections.singletonList(ChatMessage.of(RecordRole.USER, task)),
                    Collections.emptyList(), 0, null));
            if (result.usage() != null) {
                budgetService.recordUsage(session, result.usage().total());
            }
            return result.text() == null || result.text().trim().isEmpty() ? null : result.text();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private AgentDefinition agentOf(AgentId agentId) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(agentId) && agent.isActive()) {
                return agent;
            }
        }
        return null;
    }

    private static int failedIn(PlanStep step) {
        Attempt latest = step.attempts().get(step.attempts().size() - 1);
        return latest.attemptNo();
    }

    /**
     * 技能使用痕迹落在 attempt 上：改进判定要"这次用过的技能"，而内循环只认识 PlanStep。
     * 规则出处：模块-规划 §5、模块-记忆 §8.6。
     */
    private void recordSkillUsage(Plan plan, PlanStep step, Attempt attempt, IterationResult iteration) {
        if (plan == null || step == null || attempt == null || iteration.usedSkillIds().isEmpty()) {
            return;
        }
        for (SkillId skillId : iteration.usedSkillIds()) {
            plan.recordSkillUsage(step.id(), attempt.attemptNo(), skillId);
        }
        commitPlan(plan);
    }

    /** 计划完结（含终局失败）：组装事实摘要交给编排者，记忆不遍历 Plan。 */
    private void curate(Session session, Plan plan) {
        List<StepTrace> traces = new ArrayList<StepTrace>();
        for (PlanStep step : plan.steps()) {
            Attempt latest = step.attempts().isEmpty() ? null
                    : step.attempts().get(step.attempts().size() - 1);
            traces.add(new StepTrace(step.id(), step.description(), step.status(),
                    latest == null ? null : latest.recordRange(),
                    latest == null ? Collections.<SkillId>emptyList() : latest.usedSkillIds(),
                    latest == null || latest.outcome() == AttemptOutcome.SUCCEEDED ? null
                            : plan.terminationReason(),
                    latest == null || latest.reflectionId() == null ? null : reflectionOf(latest)));
        }
        planCompletionCurator.curate(session, new DistillationInput(plan.id(), session.id(), plan.goal(),
                plan.status(), traces));
    }

    private String reflectionOf(Attempt attempt) {
        return reflections.get(attempt.reflectionId()).isPresent()
                ? reflections.get(attempt.reflectionId()).get().conclusion() : null;
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
