package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanCreationException;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.planning.plan.StepStatus;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * `replan_plan`：草案校验通过才替换未执行尾部；失败当作工具失败结果回灌模型。
 * 规则出处：05-action §5.4.1、06-planning §6.8.1 规则 6、§6.9.4。
 */
public final class ReplanPlanHandler implements InternalToolHandler {

    private final PlanFactory planFactory;
    private final PlanRepository plans;
    private final CatalogRepository catalog;
    private final CommitScope commitScope;

    public ReplanPlanHandler(PlanFactory planFactory, PlanRepository plans, CatalogRepository catalog,
                             CommitScope commitScope) {
        this.planFactory = planFactory;
        this.plans = plans;
        this.catalog = catalog;
        this.commitScope = commitScope;
    }

    @Override
    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
        Plan plan = runningPlan(session);
        if (plan == null) {
            return ToolExecOutcome.plain("没有可重划的计划");
        }
        Plan draft;
        try {
            draft = planFactory.create(PlanId.of(UUID.randomUUID().toString()), session.id(), args,
                    boundTools(session));
        } catch (PlanCreationException rejected) {
            return ToolExecOutcome.plain("重划被拒绝：" + rejected.getMessage());
        }
        plan.applyReplan(draft.steps());
        commitScope.stageReplace(plan);
        commitScope.commit();
        return ToolExecOutcome.plain("计划已重划（第 " + plan.replanGeneration() + " 代）：剩余 "
                + pendingSteps(plan) + " 步");
    }

    private Plan runningPlan(Session session) {
        List<Plan> running = plans.findRunning(session.id());
        for (Plan plan : running) {
            if (plan.status() == PlanStatus.EXECUTING) {
                return plan;
            }
        }
        return null;
    }

    /** 步数只计未被 SUPERSEDED 的步骤：旧尾部会保留下来供审计（06-planning §6.8.1 规则 5）。 */
    private static int pendingSteps(Plan plan) {
        int count = 0;
        for (PlanStep step : plan.steps()) {
            if (step.status() != StepStatus.SUPERSEDED && step.status() != StepStatus.SUCCEEDED) {
                count++;
            }
        }
        return count;
    }

    private java.util.Set<ToolId> boundTools(Session session) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(session.agentId())) {
                return new LinkedHashSet<ToolId>(agent.listBoundToolIds());
            }
        }
        return java.util.Collections.emptySet();
    }
}
