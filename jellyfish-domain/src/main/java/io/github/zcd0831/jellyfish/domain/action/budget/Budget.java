package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

/** 会话族 token 台账。规则出处：模块-行动 §3.4、§7.5、§8.3。 */
public class Budget {

    private final BudgetId id;
    private final SessionId sessionFamilyRootId;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private long consumedTokens;
    private boolean warned;

    public Budget(BudgetId id, SessionId sessionFamilyRootId, long consumedTokens, boolean warned) {
        this.id = id;
        this.sessionFamilyRootId = sessionFamilyRootId;
        this.consumedTokens = consumedTokens;
        this.warned = warned;
    }

    /** 规则出处：模块-行动 §7.5 规则 ①（一族一账，随会话开启创建）。 */
    public static Budget openFor(BudgetId id, SessionId rootSessionId) {
        return new Budget(id, rootSessionId, 0L, false);
    }

    /** 只累加；首次越过阈值在这里 raise，一族只报一次。规则出处：§8.3。 */
    public void recordUsage(long tokens, RuntimePolicy policy) {
        consumedTokens += tokens;
        if (!warned && shouldWarn(policy)) {
            warned = true;
            pendingEvents.add(new BudgetWarning());
        }
    }

    /** 纯判定：`consumedTokens >= budgetLimitTokens × budgetWarnRatio`。 */
    public boolean shouldWarn(RuntimePolicy policy) {
        if (policy.budgetLimitTokens() <= 0) {
            return false;
        }
        return consumedTokens >= Math.round(policy.budgetLimitTokens() * policy.budgetWarnRatio());
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public BudgetId id() {
        return id;
    }

    public SessionId sessionFamilyRootId() {
        return sessionFamilyRootId;
    }

    public long consumedTokens() {
        return consumedTokens;
    }

    public boolean warned() {
        return warned;
    }
}
