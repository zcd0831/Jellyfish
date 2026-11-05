package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.memory.DistillationInput;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.ExperienceRetriever;
import io.github.zcd0831.jellyfish.application.memory.FeedbackInput;
import io.github.zcd0831.jellyfish.application.memory.RetrievalInput;
import io.github.zcd0831.jellyfish.application.memory.StepBrief;
import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.memory.StepTrace;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
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
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 外循环：算上下文 → 取 step → 跑一次内循环 → 验证 → 按三条出口推进。
 * 唯一有权推进计划状态机的地方。规则出处：08-application §8.9.1、模型图 §8.6.1、06-planning §6.9.1。
 */
public final class ExecutionLoop implements SessionRunner {

    private static final AgentId REFLECT_AGENT = AgentId.of("builtin.reflect");
    private static final String REPLAN_TOOL = "replan_plan";

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
    private final CancellationRegistry cancellations;
    private final ModelInvocationPort model;
    private final BudgetService budgetService;
    private final ExperienceRetriever experienceRetriever;
    private final ExperienceFeedbackService experienceFeedback;
    private final PlanCompletionCurator planCompletionCurator;
    private final ReflectionRepository reflections;
    private final io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository experiences;
    private final SessionToolScope scope;
    private final io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks;

    public ExecutionLoop(ContextWindowService contextWindow, ReActLoop reActLoop,
                         VerificationRunner verificationRunner, PlanRepository plans, PlanFactory planFactory,
                         CatalogRepository catalog, PolicyRepository policies, CommitScope commitScope,
                         DomainEventPublisher eventPublisher, ProgressPort progress, CancellationRegistry cancellations,
                         ModelInvocationPort model,
                         BudgetService budgetService, ExperienceRetriever experienceRetriever,
                         ExperienceFeedbackService experienceFeedback,
                         PlanCompletionCurator planCompletionCurator,
                         ReflectionRepository reflections,
                         io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository experiences,
                         SessionToolScope scope,
                         io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner forks) {
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
        this.cancellations = cancellations;
        this.model = model;
        this.budgetService = budgetService;
        this.experienceRetriever = experienceRetriever;
        this.experienceFeedback = experienceFeedback;
        this.planCompletionCurator = planCompletionCurator;
        this.reflections = reflections;
        this.experiences = experiences;
        this.scope = scope;
        this.forks = forks;
    }

    /**
     * `context` 只服务外部实现：默认循环的依赖由构造器注入，因此这里不读它
     * （06-planning §6.5：`RunContext` 是给插件实现的扶手）。
     * <p>令牌不走形参：一个会话一个令牌，轮次边界只认登记表（08-application §8.9.4.1）。
     */
    @Override
    public RunOutcome run(Session session, ChunkSink sink, RunContext context) {
        Plan plan = runningPlan(session);
        int replanGeneration = plan == null ? 0 : plan.replanGeneration();
        // 播报按 planId 去重：一次 run 里同一个计划只播报一次（08-application §8.7）
        Set<PlanId> announcedPlans = new HashSet<>();
        // 计划完结（含终局失败）后还要补一轮“结论交付”自由内循环：它是 do-while 退出条件的一部分
        // 规则出处：06-planning §6.8.1 规则 7、§6.9.1
        boolean deliveryPending = false;

        do {
            // 每一轮开始先看取消：流式/工具刚被打断时，不再进入下一轮
            // 令牌只从登记表取（一个会话一个令牌），不接受外部传入的形参（08-application §8.9.4.1）
            cancellations.forSession(session.id()).throwIfCancelled();

            // 计划可能在上一轮的模型响应里才诞生（generate_plan）：每轮重读；重划不改 planId
            Plan current = runningPlan(session);
            if (current != null) {
                plan = current;
            }
            boolean deliveryRound = deliveryPending && plan == null;
            deliveryPending = false;

            if (plan != null && plan.hasExecutablePlan() && announcedPlans.add(plan.id())) {
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.PLAN_CREATED,
                        "计划：" + plan.goal() + "（" + pendingSteps(plan) + " 步）"));
            }

            PlanStep step = plan != null && plan.hasExecutablePlan() ? plan.getCurrentStep() : null;
            Attempt attempt = null;
            if (step != null && step.status() == StepStatus.PENDING) {
                injectExperiences(session, step);
                attempt = plan.beginCurrentStep();
                commitPlan(plan);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_STARTED,
                        "第 " + stepProgress(plan, step) + " 步：" + step.description()));
            } else if (step != null && !step.attempts().isEmpty()) {
                attempt = step.attempts().get(step.attempts().size() - 1);
            }

            if (deliveryRound) {
                // 交付轮不挂任何工具：白名单置空，不改 tools 快照（06-planning §6.9.1）
                scope.restrict(session.id(), Collections.<ToolId>emptySet());
            }
            IterationResult iteration;
            try {
                iteration = reActLoop.runIteration(session, step, sink);
            } catch (java.util.concurrent.CancellationException terminated) {
                throw terminated;   // 终止是终态：交付轮也不吞（08-application §8.9.4.1）
            } catch (RuntimeException failure) {
                if (!deliveryRound) {
                    throw failure;   // 模型不可用：不推进状态
                }
                return RunOutcome.CONVERGED;   // 结局已定：交付轮失败静默收敛（08-application §8.10）
            } finally {
                if (deliveryRound) {
                    scope.clear(session.id());
                }
            }

            // 重划由模型在内循环里调 replan_plan 发起：外循环只按“代次变化”重读实例、
            // 重分配经验并播报。规则出处：06-planning §6.8.1 规则 6
            // 重读是必须的：replan_plan 用的是另一个 Plan 实例，写回旧实例会把重划覆盖掉
            Plan replanned = runningPlan(session);
            if (replanned != null && replanned.replanGeneration() > replanGeneration) {
                replanGeneration = replanned.replanGeneration();
                plan = replanned;
                // 经验由 replan_plan 工具在迁移时分配好：这里只播报（06-planning §6.8.1 规则 6）
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.PLAN_REPLAN,
                        "重划（第 " + plan.replanGeneration() + " 代）：" + plan.goal()
                                + "（" + pendingSteps(plan) + " 步）"));
                // applyReplan 只替换未执行的尾部：执行中的 step 仍在，按 id 重新解析到新实例上
                if (step != null) {
                    PlanStep reloaded = plan.getCurrentStep();
                    if (reloaded != null) {
                        step = reloaded;
                    }
                    if (!step.attempts().isEmpty()) {
                        attempt = step.attempts().get(step.attempts().size() - 1);
                    }
                }
            } else if (plan == null && replanned != null) {
                plan = replanned;   // 自由内循环里 generate_plan 诞生了新计划（03-overview §3.7）
                assignExperiences(session, plan);
            }

            // 技能使用痕迹落在 attempt 上：改进判定要“这次用过的技能”
            recordSkillUsage(plan, step, attempt, iteration);

            if (!iteration.isConverged()) {
                // 撞 maxReActRounds 不是业务失败：提示后挂起，不判失败、不消耗重试
                // 规则出处：06-planning §6.8.1 约束表、§6.9.1 关键分支
                if (deliveryRound) {
                    return RunOutcome.CONVERGED;
                }
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.ROUNDS_EXHAUSTED,
                        "已达轮次上限（" + iteration.round() + " 轮），等你再说一句"));
                return RunOutcome.SUSPENDED;
            }

            if (deliveryRound) {
                return RunOutcome.CONVERGED;   // 交付轮跑完即本轮交互结束（06-planning §6.9.1）
            }

            if (step == null) {
                // 无 step 的自由轮：generate_plan 成功后下一轮转入计划执行；否则本轮交互结束
                if (plan != null && plan.hasExecutablePlan()) {
                    continue;
                }
                return RunOutcome.CONVERGED;
            }

            // 这一步没有调 submit_step：step 还没结束，挂起把发言权还给用户
            if (!iteration.submitted()) {
                return RunOutcome.SUSPENDED;
            }

            if (!stepNeedsVerification(step)) {
                plan.markStepSucceeded(step.id(), iteration.submittedSummary(), null);
                commitPlan(plan);
                feedbackSucceeded(step);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                        "第 " + stepProgress(plan, step) + " 步完成（免验证）"));
            } else {
                VerificationReport report = verificationRunner.verify(session, plan, step,
                        attempt == null ? 0 : attempt.attemptNo(), iteration.submittedSummary(),
                        iteration.submittedArtifacts());
                commitScope.stageFact(report);
                if (report.outcome() == VerificationOutcome.PASSED) {
                    plan.markStepSucceeded(step.id(), iteration.submittedSummary(), report.id());
                    commitPlan(plan);
                    feedbackSucceeded(step);
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + stepProgress(plan, step) + " 步验收通过"));
                } else {
                    plan.markStepFailed(step.id(), report.id());
                    publish(report.pullEvents());
                    feedbackFailed(step);
                    reflectIfNeeded(session, plan, step, report.failureReason());
                    progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                            "第 " + stepProgress(plan, step)
                                    + " 步验收未通过：" + report.outcome()));
                    boolean terminated = advanceAfterFailure(session, plan, step,
                            report.failureReason() == null ? String.valueOf(report.outcome())
                                    : report.failureReason());
                    // 判败与随后那一步（重试 / 纠偏 / 终止）是**同一次状态转移**，只提一次：
                    // 拆成两次提交时，中间那个窗口里盘上是「EXECUTING 但没有当前步」，
                    // 续跑时外循环只能当自由轮收敛，generate_plan 又因"已有执行中的计划"拒绝，
                    // 该会话从此再也规划不了（08-application §8.8.2 T7、§8.8.1）
                    commitPlan(plan);
                    if (terminated) {
                        publish(plan.pullEvents());
                        curate(session, plan);
                        plan = null;
                        deliveryPending = true;   // 终局失败同构：沉淀后补一轮结论交付
                    }
                    continue;
                }
            }

            if (plan.status() == PlanStatus.COMPLETED) {
                // 计划完结：先沉淀，再补一轮“结论交付”自由内循环（06-planning §6.9.1）
                publish(plan.pullEvents());
                curate(session, plan);
                plan = null;
                deliveryPending = true;
            }
        } while (deliveryPending || (plan != null && plan.hasExecutablePlan()));

        return RunOutcome.CONVERGED;
    }

    /**
     * 失败后的推进：先重试；重试用尽则注入一条纠偏指令并给一次纠偏 attempt（不占 maxRetries），
     * 重划由模型在该 attempt 内调 `replan_plan` 自愿发起；纠偏仍未成功则终止。
     * 「还能不能重试 / 该不该重划」由这里判定，聚合只执行迁移（06-planning §6.8.1）。
     */
    private boolean advanceAfterFailure(Session session, Plan plan, PlanStep step, String reason) {
        RuntimePolicy policy = policies.load();
        if (failedAttempts(step) < policy.maxRetries()) {
            plan.retryStep(step.id());
            return false;
        }
        // 重试用尽：给一次纠偏机会（不占重试名额，上限由重划代次兜住）
        // 规则出处：06-planning §6.8.1 规则 6、约束表
        if (!alreadyCorrected(session, step) && plan.replanGeneration() < policy.maxReplanGeneration()) {
            injectReplanDirective(session, step, reason);   // 只 stage，跟计划一起提交
            plan.retryStep(step.id());
            return false;
        }
        plan.terminate("重划用尽：" + reason);
        return true;
    }

    /**
     * 纠偏指令：告诉模型这一步连续失败了几次，可以调 `replan_plan` 修正计划，或换一种做法重做。
     * 注入形态与经验注入一致：一条 SYSTEM 记录，首行带 step 标记用于去重（标记不落库）。
     * 规则出处：06-planning §6.8.1 规则 6。
     */
    private void injectReplanDirective(Session session, PlanStep step, String reason) {
        String text = correctionMarker(step)
                + "\n该步已连续失败 " + failedAttempts(step) + " 次：" + reason
                + "\n请调用 " + REPLAN_TOOL + " 修正剩余步骤；或换一种做法重做这一步。";
        Record injected = session.appendRecord(RecordRole.SYSTEM, text, null, System.currentTimeMillis());
        commitScope.stageAppend(session.id(), injected);   // 调用方与计划变更一起提交
    }

    private static String correctionMarker(PlanStep step) {
        return "[重划纠偏 step=" + step.id().getValue() + "]";
    }

    /** 去重靠流水本身：每个 step 最多一次纠偏（06-planning §6.8.1 规则 6）。 */
    private static boolean alreadyCorrected(Session session, PlanStep step) {
        String marker = correctionMarker(step);
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM && record.content() != null
                    && record.content().startsWith(marker)) {
                return true;
            }
        }
        return false;
    }
    /**
     * 计划诞生 / 重划：检索经验并交给 Plan 自己改状态（记忆只返回分配结果）。
     */
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
     * 规则出处：04-perception §4.8.1 规则 9 ①（经验注入）、规则 10（不参与淘汰）、§7.3 规则 3。
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

    /**
     * 去重靠流水本身：标记是"可推导状态"，不落库。
     */
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

    /**
     * 本期"注入"与"建议"同源：分配即注入（奖惩不对称仍按两段分支执行）。
     */
    private static FeedbackInput feedbackOf(PlanStep step) {
        return new FeedbackInput(step.id(), step.assignedExperienceIds());
    }

    /**
     * 达反思阈值时跑内置反思子代理（按文档是子代理，不是进程内调用），结论落 Reflection 并回注。
     * 规则出处：07-memory §7.8.2、§7.9.1 回注。
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
     * 反思 = fork 当前生效窗口 + 末尾“反思指令 + 失败原因”：轨迹从窗口里来，不再单独拼素材。
     * 代理缺失或调用失败 ⇒ 本次不反思：把错误文案存成反思结论比不反思更糟；
     * 但**终止要上抛**，否则取消被吞后仍会走重试/重划（08-application §8.9.4.1）。
     * 规则出处：07-memory §7.8.2、08-application §8.9.7。
     */
    private String reflect(Session session, PlanStep step, String failureReason) {
        AgentDefinition agent = agentOf(REFLECT_AGENT);
        if (agent == null) {
            return null;
        }
        String instruction = agent.basePrompt().getTemplateText()
                + "\n\n原定目标：" + step.description() + "\n失败原因：" + failureReason
                + "\n请给出下一次该改什么。";
        try {
            String conclusion = forks.run(session, instruction);
            return conclusion == null || conclusion.trim().isEmpty() ? null : conclusion;
        } catch (java.util.concurrent.CancellationException terminated) {
            // 终止是终态：不当成“本次不反思”，也不再推进状态（08-application §8.9.4.1、§8.9.7）
            throw terminated;
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
     * 规则出处：06-planning §6.6、07-memory §7.9.6。
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

    /**
     * 计划完结（含终局失败）：组装事实摘要交给编排者，记忆不遍历 Plan。
     */
    private void curate(Session session, Plan plan) {
        List<StepTrace> traces = new ArrayList<StepTrace>();
        for (PlanStep step : plan.steps()) {
            Attempt latest = step.attempts().isEmpty() ? null
                    : step.attempts().get(step.attempts().size() - 1);
            traces.add(new StepTrace(step.id(), step.description(), step.status(),
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

    /**
     * 播报里的「（N 步）」：剩余待做步数，不含已完成、不含被替换的旧尾部（08-application §8.7）。
     */
    private static int pendingSteps(Plan plan) {
        int count = 0;
        for (PlanStep step : plan.steps()) {
            if (step.status() != StepStatus.SUPERSEDED && step.status() != StepStatus.SUCCEEDED) {
                count++;
            }
        }
        return count;
    }

    /**
     * 进度文案里的「第 x/N 步」：x 与 N 同一口径，N 只计未 SUPERSEDED 的总步数。
     * 不能用 step.sequence()/steps().size()：重划会保留被替换的旧尾部，两个数都会虚高。
     * 规则出处：08-application §8.7。
     */
    private static String stepProgress(Plan plan, PlanStep step) {
        int total = 0;
        int position = 0;
        for (PlanStep each : plan.steps()) {
            if (each.status() == StepStatus.SUPERSEDED) {
                continue;
            }
            total++;
            if (each.id().equals(step.id())) {
                position = total;
            }
        }
        return position + "/" + total;
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
        // 同一会话同时只有一个可执行计划（06-planning §6.8.1 规则 2 的会话侧投影）
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
