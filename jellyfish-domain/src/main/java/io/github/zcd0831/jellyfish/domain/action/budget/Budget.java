package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/** 会话族 token 台账。规则出处：模块-行动 §3.4、§7.5 规则 ①②。 */
public class Budget {

    private final BudgetId id;
    private final SessionId sessionFamilyRootId;

    private long consumedTokens;
    private boolean warned;

    public Budget(BudgetId id, SessionId sessionFamilyRootId, long consumedTokens, boolean warned) {
        this.id = id;
        this.sessionFamilyRootId = sessionFamilyRootId;
        this.consumedTokens = consumedTokens;
        this.warned = warned;
    }

    /** 规则出处：模块-行动 §7.5 规则 ①（一族一账）。 */
    public static Budget openFor(BudgetId id, SessionId rootSessionId) {
        return new Budget(id, rootSessionId, 0L, false);
    }

    /** 只累加、不预留、不中断。规则出处：模块-行动 §7.5 规则 ②③。 */
    public void recordUsage(long tokens) {
        consumedTokens += tokens;
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
