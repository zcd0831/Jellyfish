package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外循环：算上下文 → 取 step → 跑一次内循环 → 验证 → 按三条出口推进。
 * 唯一有权推进计划状态机的地方。规则出处：08-application §8.9.1、模型图 §8.6.1、06-planning §6.9.1。
 * <p>一轮的五个阶段各自成方法：{@code reloadPlan}（重读实例）→ {@code terminateIfNoCurrentStep}
 * → {@code openStep}（起 attempt / 收白名单）→ {@code iterate}（跑内循环）→ {@code settle}（验证与推进）。
 */
public final class ExecutionLoop implements SessionRunner {

    private static final AgentId REFLECT_AGENT = AgentId.of("builtin.reflect");
    private static final String REPLAN_TOOL = "replan_plan";
    /** 步骤都终结了、计划还挂在 EXECUTING：外循环自己收口（06-planning §6.8.1「无当前步即终结」）。 */
    private static final String NO_EXECUTABLE_STEP = "所有步骤已终结，没有可执行的步骤";

    private final ReActLoop reActLoop;
    private final VerificationRunner verificationRunner;
    private final PlanRepository plans;
    private final PlanFactory planFactory;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;
    private final CancellationRegistry cancellations;
    private final PlanSettlement settlement;
    private final StepContextInjector injector;
    private final FailureReflection reflection;
    private final SessionToolScope scope;

    public ExecutionLoop(ExecutionLoopDeps deps) {
        this.reActLoop = deps.require(deps.reActLoop(), "reActLoop");
        this.verificationRunner = deps.require(deps.verificationRunner(), "verificationRunner");
        this.plans = deps.require(deps.plans(), "plans");
        this.planFactory = deps.require(deps.planFactory(), "planFactory");
        this.policies = deps.require(deps.policies(), "policies");
        this.commitScope = deps.require(deps.commitScope(), "commitScope");
        this.eventPublisher = deps.require(deps.eventPublisher(), "eventPublisher");
        this.progress = deps.require(deps.progress(), "progress");
        this.cancellations = deps.require(deps.cancellations(), "cancellations");
        this.settlement = new PlanSettlement(
                deps.require(deps.experienceFeedback(), "experienceFeedback"),
                deps.require(deps.planCompletionCurator(), "planCompletionCurator"),
                deps.require(deps.reflections(), "reflections"),
                deps.require(deps.commitScope(), "commitScope"));
        this.injector = new StepContextInjector(
                deps.require(deps.experiences(), "experiences"),
                deps.require(deps.commitScope(), "commitScope"));
        this.reflection = new FailureReflection(
                deps.require(deps.catalog(), "catalog"),
                deps.require(deps.policies(), "policies"),
                deps.require(deps.commitScope(), "commitScope"),
                deps.require(deps.forks(), "forks"));
        this.scope = deps.require(deps.scope(), "scope");
    }

    /**
     * `context` 只服务外部实现：默认循环的依赖由构造器注入，因此这里不读它
     * （06-planning §6.5：`RunContext` 是给插件实现的扶手）。
     * <p>令牌不走形参：一个会话一个令牌，轮次边界只认登记表（08-application §8.9.4.1）。
     */
    @Override
    public RunOutcome run(Session session, ChunkSink sink, RunContext context) {
        RunState state = new RunState(runningPlan(session));
        do {
            // 每一轮开始先看取消：流式/工具刚被打断时，不再进入下一轮
            // 令牌只从登记表取（一个会话一个令牌），不接受外部传入的形参（08-application §8.9.4.1）
            cancellations.forSession(session.id()).throwIfCancelled();

            reloadPlan(session, state);
            if (terminateIfNoCurrentStep(session, state)) {
                continue;   // 判失败并补交付轮：条件里 deliveryPending 已经就位
            }
            boolean deliveryRound = state.beginRound();
            announcePlan(state);
            openStep(session, state, deliveryRound);

            IterationResult iteration = iterate(session, state, deliveryRound, sink);
            if (iteration == null) {
                return RunOutcome.CONVERGED;   // 结局已定：交付轮失败静默收敛（08-application §8.10）
            }
            trackReplan(session, state, iteration);

            RoundOutcome outcome = settle(session, state, iteration);
            if (outcome != RoundOutcome.CONTINUE) {
                return outcome.asRunOutcome();
            }
        } while (state.deliveryPending() || state.hasExecutablePlan());

        return RunOutcome.CONVERGED;
    }

    /**
     * 计划可能在上一轮的模型响应里才诞生（generate_plan）：每轮重读。
     * 重读也是必须的：`replan_plan` 用的是另一个 Plan 实例，写回旧实例会把重划覆盖掉。
     */
    private void reloadPlan(Session session, RunState state) {
        Plan current = runningPlan(session);
        if (current != null) {
            state.plan(current);   // 只刷新实例；代次变化由 trackReplan 负责播报
        }
    }

    /**
     * 步骤都终结了、计划还挂在 EXECUTING：外循环自己收口，不押在启动对账上。
     * 与对账同一口径、幂等；收口后同样补一轮结论交付。
     * 规则出处：06-planning §6.8.1「无当前步即终结」。
     */
    private boolean terminateIfNoCurrentStep(Session session, RunState state) {
        Plan plan = state.plan();
        if (plan == null || !plan.failIfNoCurrentStep(NO_EXECUTABLE_STEP)) {
            return false;
        }
        commitPlan(plan);
        retirePlan(session, state);
        return true;
    }

    /** 播报按 planId 去重：一次 run 里同一个计划只播报一次（08-application §8.6）。 */
    private void announcePlan(RunState state) {
        Plan plan = state.plan();
        if (plan == null || !plan.hasExecutablePlan() || !state.announceOnce(plan.id())) {
            return;
        }
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.PLAN_CREATED,
                "计划：" + plan.goal() + "（" + PlanProgress.pendingSteps(plan) + " 步）"));
    }

    /**
     * 起这一步：注入经验 → 建 attempt → 落盘 → 播报；交付轮把工具白名单置空。
     * 白名单置空不动 tools 快照（06-planning §6.9.1）。
     */
    private void openStep(Session session, RunState state, boolean deliveryRound) {
        state.resolveStep();
        Plan plan = state.plan();
        PlanStep step = state.step();
        if (step != null && step.status() == io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus.PENDING) {
            injector.injectExperiences(session, step);
            state.startedAttempt(plan.beginCurrentStep());
            commitPlan(plan);
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_STARTED,
                    "第 " + PlanProgress.position(plan, step) + " 步：" + step.description()));
        }
        if (deliveryRound) {
            scope.restrict(session.id(), Collections.<ToolId>emptySet());
        }
    }

    /**
     * 跑一次内循环，并播报降级换模型。
     * 终止是终态（交付轮也不吞）；模型不可用只在交付轮静默收敛（08-application §8.9.4.1、§8.10）。
     */
    private IterationResult iterate(Session session, RunState state, boolean deliveryRound, ChunkSink sink) {
        ModelId modelBefore = session.activeModelId();
        IterationResult iteration;
        try {
            iteration = reActLoop.runIteration(session, state.step(), sink);
        } catch (java.util.concurrent.CancellationException terminated) {
            throw terminated;
        } catch (RuntimeException failure) {
            if (!deliveryRound) {
                throw failure;   // 模型不可用：不推进状态
            }
            return null;
        } finally {
            if (deliveryRound) {
                scope.clear(session.id());
            }
        }
        announceDowngrade(session, modelBefore);
        return iteration;
    }

    /**
     * 重划由模型在内循环里调 `replan_plan` 发起：外循环只按"代次变化"重读实例并播报。
     * 经验在 `replan_plan` 处理器里随迁移一次分配好（06-planning §6.8.1 规则 6）。
     */
    private void trackReplan(Session session, RunState state, IterationResult iteration) {
        Plan replanned = runningPlan(session);
        if (replanned != null && replanned.replanGeneration() > state.replanGeneration()) {
            state.adoptReplan(replanned);
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.PLAN_REPLAN,
                    "重划（第 " + replanned.replanGeneration() + " 代）：" + replanned.goal()
                            + "（" + PlanProgress.pendingSteps(replanned) + " 步）"));
        } else if (replanned != null && state.plan() == null) {
            state.plan(replanned);   // 自由内循环里 generate_plan 诞生了新计划（03-overview §3.7）
        }
        settlement.recordSkillUsage(state.plan(), state.step(), state.attempt(), iteration);
    }

    /** 一轮的收口：三条出口 + 验证 + 推进。 */
    private RoundOutcome settle(Session session, RunState state, IterationResult iteration) {
        if (!iteration.isConverged()) {
            return settleRoundsExhausted(state, iteration);
        }
        if (state.deliveryRound()) {
            return RoundOutcome.CONVERGED;   // 交付轮跑完即本轮交互结束（06-planning §6.9.1）
        }
        if (state.step() == null) {
            // 无 step 的自由轮：generate_plan 成功后下一轮转入计划执行；否则本轮交互结束
            return state.hasExecutablePlan() ? RoundOutcome.CONTINUE : RoundOutcome.CONVERGED;
        }
        if (!iteration.submitted()) {
            return RoundOutcome.SUSPENDED;   // 没调 submit_step：把发言权还给用户
        }
        boolean terminated = verifyAndAdvance(session, state, iteration);
        if (terminated || state.plan().status() == PlanStatus.COMPLETED) {
            retirePlan(session, state);
        }
        return RoundOutcome.CONTINUE;
    }

    /**
     * 撞 maxReActRounds 不是业务失败：提示后挂起，不判失败、不消耗重试。
     * 规则出处：06-planning §6.8.1 约束表、§6.9.1 关键分支。
     */
    private RoundOutcome settleRoundsExhausted(RunState state, IterationResult iteration) {
        if (state.deliveryRound()) {
            return RoundOutcome.CONVERGED;
        }
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.ROUNDS_EXHAUSTED,
                "已达轮次上限（" + iteration.round() + " 轮），等你再说一句"));
        return RoundOutcome.SUSPENDED;
    }

    /** 验证并推进；返回 true 表示这一步以"计划终止"收场。 */
    private boolean verifyAndAdvance(Session session, RunState state, IterationResult iteration) {
        Plan plan = state.plan();
        PlanStep step = state.step();
        if (!PlanProgress.needsVerification(step)) {
            plan.markStepSucceeded(step.id(), iteration.submittedSummary(), null);
            commitPlan(plan);
            settlement.stepSucceeded(step);
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                    "第 " + PlanProgress.position(plan, step) + " 步完成（免验证）"));
            return false;
        }
        VerificationReport report = verificationRunner.verify(session, plan, step,
                state.attempt() == null ? 0 : state.attempt().attemptNo(), iteration.submittedSummary(),
                iteration.submittedArtifacts());
        commitScope.stageFact(report);
        if (report.outcome() == VerificationOutcome.PASSED) {
            passStep(state, report, iteration);
            return false;
        }
        return failStep(session, state, report);
    }

    private void passStep(RunState state, VerificationReport report, IterationResult iteration) {
        Plan plan = state.plan();
        PlanStep step = state.step();
        plan.markStepSucceeded(step.id(), iteration.submittedSummary(), report.id());
        commitPlan(plan);
        publish(report.pullEvents());   // 事件在提交后发（08-application §8.8.3 纪律 2）
        settlement.stepSucceeded(step);
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                "第 " + PlanProgress.position(plan, step) + " 步完成"));
    }

    /**
     * 判败 → 反思 → 重试/纠偏/终止，**一次提交**。
     * <p>拆成两次提交时，中间那个窗口里盘上是「EXECUTING 但没有当前步」，续跑时外循环只能当
     * 自由轮收敛，`generate_plan` 又因"已有执行中的计划"拒绝，该会话从此再也规划不了。
     * 反思若自己 commit 一次，同样会重开这个窗口（08-application §8.8.2 T7、§8.8.1）。
     */
    private boolean failStep(Session session, RunState state, VerificationReport report) {
        Plan plan = state.plan();
        PlanStep step = state.step();
        String reason = report.failureReason() == null ? String.valueOf(report.outcome())
                : report.failureReason();
        plan.markStepFailed(step.id(), report.id());
        settlement.stepFailed(step);
        Reflection reflectionFact = reflection.reflectIfNeeded(session, plan, step, reason);
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.STEP_FINISHED,
                "第 " + PlanProgress.position(plan, step) + " 步失败：" + reason));
        boolean terminated = advanceAfterFailure(session, plan, step, reason);
        commitPlan(plan);
        publish(report.pullEvents());
        if (reflectionFact != null) {
            publish(reflectionFact.pullEvents());
        }
        return terminated;
    }

    /** 计划退场：发计划类事件 → 沉淀 → 补一轮结论交付。 */
    private void retirePlan(Session session, RunState state) {
        Plan plan = state.plan();
        publish(plan.pullEvents());
        settlement.curate(session, plan);
        state.retirePlan();
    }

    /** 一轮的三种走向：继续下一轮、挂起等用户、本轮结束。 */
    private enum RoundOutcome {
        CONTINUE, SUSPENDED, CONVERGED;

        RunOutcome asRunOutcome() {
            return this == SUSPENDED ? RunOutcome.SUSPENDED : RunOutcome.CONVERGED;
        }
    }

    /**
     * 失败后的推进：先重试；重试用尽则注入一条纠偏指令并给一次纠偏 attempt（不占 maxRetries），
     * 重划由模型在该 attempt 内调 `replan_plan` 自愿发起；纠偏仍未成功则终止。
     * 「还能不能重试 / 该不该重划」由这里判定，聚合只执行迁移（06-planning §6.8.1）。
     */
    private boolean advanceAfterFailure(Session session, Plan plan, PlanStep step, String reason) {
        RuntimePolicy policy = policies.load();
        if (PlanProgress.failedAttempts(step) < policy.maxRetries()) {
            plan.retryStep(step.id());
            return false;
        }
        // 重试用尽：给一次纠偏机会（不占重试名额，上限由重划代次兜住）
        // 规则出处：06-planning §6.8.1 规则 6、约束表
        if (!PlanProgress.alreadyCorrected(session, step)
                && plan.replanGeneration() < policy.maxReplanGeneration()) {
            injector.injectCorrection(session, step, reason, PlanProgress.failedAttempts(step));
            plan.retryStep(step.id());
            return false;
        }
        plan.terminate("重划用尽：" + reason);
        return true;
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

    /**
     * 降级换模型必须让用户看见：内循环只改会话状态，播报统一在外循环（08-application §8.6 阶段类）。
     */
    private void announceDowngrade(Session session, ModelId before) {
        ModelId now = session.activeModelId();
        if (before.equals(now)) {
            return;
        }
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.MODEL_SWITCHED,
                "模型降级：" + before.getValue() + " → " + now.getValue()));
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
