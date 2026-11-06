package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次 `run` 的进行中状态：当前计划 / 当前步 / 当前 attempt / 重划代次 / 是否还欠一轮结论交付。
 * <p>抽出来是为了让外循环的每个阶段都只读它、只改它，而不是在 170 行的方法体里
 * 传递七八个平行局部变量（读数最容易错的地方）。
 */
final class RunState {

    private final Set<PlanId> announcedPlans = new HashSet<PlanId>();
    private Plan plan;
    private PlanStep step;
    private Attempt attempt;
    private int replanGeneration;
    private boolean deliveryPending;
    private boolean deliveryRound;

    RunState(Plan plan) {
        this.plan = plan;
        this.replanGeneration = plan == null ? 0 : plan.replanGeneration();
    }

    Plan plan() {
        return plan;
    }

    void plan(Plan value) {
        this.plan = value;
    }

    PlanStep step() {
        return step;
    }

    Attempt attempt() {
        return attempt;
    }

    int replanGeneration() {
        return replanGeneration;
    }

    boolean deliveryPending() {
        return deliveryPending;
    }

    void deliveryPending(boolean value) {
        this.deliveryPending = value;
    }

    /** 本轮是不是"计划完结后的结论交付轮"：算一次就清掉待办，避免下一轮误判。 */
    boolean beginRound() {
        deliveryRound = deliveryPending && plan == null;
        deliveryPending = false;
        return deliveryRound;
    }

    boolean deliveryRound() {
        return deliveryRound;
    }

    /** 播报按 planId 去重：一次 run 里同一个计划只播报一次（重划不改 planId）。 */
    boolean announceOnce(PlanId planId) {
        return announcedPlans.add(planId);
    }

    /** 每轮按照最新计划重新解析当前步与 attempt：重读的实例可能已经换过（重划/续跑）。 */
    void resolveStep() {
        step = plan != null && plan.hasExecutablePlan() ? plan.getCurrentStep() : null;
        attempt = null;
        if (step == null) {
            return;
        }
        if (step.status() == StepStatus.PENDING) {
            return;   // 待启动：由循环建 attempt
        }
        if (!step.attempts().isEmpty()) {
            attempt = step.attempts().get(step.attempts().size() - 1);
        }
    }

    void startedAttempt(Attempt value) {
        this.attempt = value;
    }

    /** 重划后按 id 重新解析到新实例上（applyReplan 只替换未执行的尾部）。 */
    void adoptReplan(Plan replanned) {
        this.plan = replanned;
        this.replanGeneration = replanned.replanGeneration();
        if (step == null) {
            return;
        }
        PlanStep reloaded = replanned.getCurrentStep();
        if (reloaded != null) {
            step = reloaded;
        }
        if (!step.attempts().isEmpty()) {
            attempt = step.attempts().get(step.attempts().size() - 1);
        }
    }

    /** 计划退场（完结或终止）：沉淀后还要补一轮结论交付。 */
    void retirePlan() {
        this.plan = null;
        this.step = null;
        this.attempt = null;
        this.deliveryPending = true;
    }

    boolean hasExecutablePlan() {
        return plan != null && plan.hasExecutablePlan();
    }
}
