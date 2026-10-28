package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.action.budget.BudgetWarning;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把会话族内任意位置的消耗归集到根台账。规则出处：05-action §5.5、§5.9.4。
 */
public final class BudgetService {

    private final BudgetRepository budgets;
    private final SessionRepository sessions;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ProgressPort progress;

    public BudgetService(BudgetRepository budgets, SessionRepository sessions, PolicyRepository policies,
                         CommitScope commitScope, DomainEventPublisher eventPublisher, ProgressPort progress) {
        this.budgets = budgets;
        this.sessions = sessions;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.progress = progress;
    }

    /**
     * 返回 true 表示本次越过阈值、调用方应询问用户是否继续（不中断）。
     * 顺手记下本轮请求的输入用量，界面提示符据此显示"实际 / 上限"（05-action §5.9.4）。
     */
    public boolean recordUsage(Session session, ModelUsage usage) {
        SessionId rootId = rootOf(session);
        RuntimePolicy policy = policies.load();
        Budget budget = budgets.load(rootId);
        if (budget == null) {
            budget = Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootId);
        }
        long tokens = usage.total();
        budget.recordUsage(tokens, policy);
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.USAGE,
                "+" + tokens + " / 累计 " + budget.consumedTokens() + " / 上限 " + policy.budgetLimitTokens()));
        commitScope.stageReplace(budget);
        commitScope.commit();

        List<DomainEvent> events = budget.pullEvents();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(events));
        for (DomainEvent event : events) {
            if (event instanceof BudgetWarning) {
                return true;
            }
        }
        return false;
    }

    /**
     * 子会话结束后一次性归集：子会话不写自己的台账，避免并发写同一聚合。
     * 规则出处：05-action §5.8.5 规则 5、§5.9.1。
     */
    public void settleFromSubSession(SessionId subSessionId, long tokens) {
        Session sub = sessions.load(subSessionId);
        SessionId rootId = sub == null ? subSessionId : rootOf(sub);
        RuntimePolicy policy = policies.load();
        Budget budget = budgets.load(rootId);
        if (budget == null) {
            budget = Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootId);
        }
        budget.recordUsage(tokens, policy);
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.USAGE,
                "+" + tokens + " / 累计 " + budget.consumedTokens() + " / 上限 " + policy.budgetLimitTokens()));
        commitScope.stageReplace(budget);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(budget.pullEvents()));
    }

    /**
     * 主循环的请求：记账之外再记"本轮实际注入量"。
     * 辅助调用（生成标题、检索经验、反思、摘要…）不走这里——它们的小请求会覆盖真实窗口的数字。
     * 规则出处：05-action §5.4.4。
     */
    public boolean recordTurnUsage(Session session, ModelUsage usage) {
        boolean warned = recordUsage(session, usage);
        if (usage.inputTokens() > 0) {
            SessionId rootId = rootOf(session);
            Budget budget = budgets.load(rootId);
            if (budget == null) {
                budget = Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootId);
            }
            budget.observePrompt(session.id(), usage.inputTokens(), usage.estimated());
            commitScope.stageReplace(budget);
            commitScope.commit();
        }
        return warned;
    }

    public Budget budgetOf(SessionId rootId) {
        return budgets.load(rootId);
    }

    /** 族台账：界面读观测值时用（会话可能在族内任意位置）。 */
    public Budget budgetOf(Session session) {
        return budgets.load(rootOf(session));
    }

    /** 折叠后清掉观测：旧数值不再描述当前窗口（05-action §5.9.4）。 */
    public void forgetPromptObservation(Session session) {
        SessionId rootId = rootOf(session);
        Budget budget = budgets.load(rootId);
        if (budget == null || budget.lastPromptSessionId() == null) {
            return;
        }
        budget.forgetPromptObservation();
        commitScope.stageReplace(budget);
        commitScope.commit();
    }

    /** 子会话不自建台账：沿 parentSessionId 上溯到族根（05-action §5.8.5 规则 5）。 */
    private SessionId rootOf(Session session) {
        SessionId current = session.id();
        SessionId parent = session.parentSessionId();
        int guard = 0;
        while (parent != null && guard++ < 16) {
            current = parent;
            Session ancestor = sessions.load(parent);
            parent = ancestor == null ? null : ancestor.parentSessionId();
        }
        return current;
    }
}
