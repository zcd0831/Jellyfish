package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanCreationException;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * `generate_plan`：草案校验通过才建计划；失败当作工具失败结果回灌模型，不消耗重试。
 * 规则出处：05-action §5.4.1、06-planning §6.9.4。
 */
public final class GeneratePlanHandler implements InternalToolHandler {

    private final PlanFactory planFactory;
    private final PlanRepository plans;
    private final CatalogRepository catalog;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;

    public GeneratePlanHandler(PlanFactory planFactory, PlanRepository plans, CatalogRepository catalog,
                               CommitScope commitScope, DomainEventPublisher eventPublisher) {
        this.planFactory = planFactory;
        this.plans = plans;
        this.catalog = catalog;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
        Plan plan;
        try {
            plan = planFactory.create(PlanId.of(UUID.randomUUID().toString()), session.id(), args,
                    boundTools(session));
        } catch (PlanCreationException rejected) {
            return ToolExecOutcome.plain("计划被拒绝：" + rejected.getMessage());
        }
        commitScope.stageReplace(plan);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(plan.pullEvents()));
        return ToolExecOutcome.plain("计划已创建：" + plan.steps().size() + " 步");
    }

    private java.util.Set<io.github.zcd0831.jellyfish.domain.shared.ToolId> boundTools(Session session) {
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(session.agentId())) {
                return new java.util.LinkedHashSet<io.github.zcd0831.jellyfish.domain.shared.ToolId>(
                        agent.listBoundToolIds());
            }
        }
        return Collections.emptySet();
    }
}
