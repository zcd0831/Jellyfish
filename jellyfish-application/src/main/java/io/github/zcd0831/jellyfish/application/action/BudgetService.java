package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
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
 * 把会话族内任意位置的消耗归集到根台账。规则出处：模块-行动 §4、§8.3。
 */
public final class BudgetService {

    private final BudgetRepository budgets;
    private final SessionRepository sessions;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;

    public BudgetService(BudgetRepository budgets, SessionRepository sessions, PolicyRepository policies,
                         CommitScope commitScope, DomainEventPublisher eventPublisher) {
        this.budgets = budgets;
        this.sessions = sessions;
        this.policies = policies;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
    }

    /** 返回 true 表示本次越过阈值、调用方应询问用户是否继续（不中断）。 */
    public boolean recordUsage(Session session, long tokens) {
        SessionId rootId = rootOf(session);
        RuntimePolicy policy = policies.load();
        Budget budget = budgets.load(rootId);
        if (budget == null) {
            budget = Budget.openFor(BudgetId.of(UUID.randomUUID().toString()), rootId);
        }
        budget.recordUsage(tokens, policy);
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

    /** 子会话不自建台账：沿 parentSessionId 上溯到族根（模块-行动 §7.5 规则 ⑤）。 */
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
