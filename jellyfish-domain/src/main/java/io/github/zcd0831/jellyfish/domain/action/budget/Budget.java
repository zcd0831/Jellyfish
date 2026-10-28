package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

/** 会话族 token 台账。规则出处：05-action §5.4.4、§5.8.5、§5.9.4。 */
public class Budget {

    private final BudgetId id;
    private final SessionId sessionFamilyRootId;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private long consumedTokens;
    private boolean warned;
    /** 最近一次模型请求的 input tokens：只喂界面展示，不参与折叠判定（05-action §5.4.4）。 */
    private long lastPromptTokens;
    private boolean lastPromptEstimated;
    private String lastPromptSessionId;

    public Budget(BudgetId id, SessionId sessionFamilyRootId, long consumedTokens, boolean warned) {
        this(id, sessionFamilyRootId, consumedTokens, warned, 0L, false, null);
    }

    public Budget(BudgetId id, SessionId sessionFamilyRootId, long consumedTokens, boolean warned,
                  long lastPromptTokens, boolean lastPromptEstimated, String lastPromptSessionId) {
        this.id = id;
        this.sessionFamilyRootId = sessionFamilyRootId;
        this.consumedTokens = consumedTokens;
        this.warned = warned;
        this.lastPromptTokens = lastPromptTokens;
        this.lastPromptEstimated = lastPromptEstimated;
        this.lastPromptSessionId = lastPromptSessionId;
    }

    /** 规则出处：05-action §5.8.5 规则 1（一族一账，随会话开启创建）。 */
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

    /**
     * 记下"这一轮请求实际注入了多少"：厂商给的精确值，或本地估算（estimated = true）。
     * 规则出处：05-action §5.4.4（顺带记一次观测）。
     */
    public void observePrompt(SessionId sessionId, long promptTokens, boolean estimated) {
        this.lastPromptSessionId = sessionId.getValue();
        this.lastPromptTokens = promptTokens;
        this.lastPromptEstimated = estimated;
    }

    /** 折叠后旧数值不再描述当前窗口，清掉让界面回到 `—`（05-action §5.9.4）。 */
    public void forgetPromptObservation() {
        this.lastPromptSessionId = null;
        this.lastPromptTokens = 0L;
        this.lastPromptEstimated = false;
    }

    public long lastPromptTokens() {
        return lastPromptTokens;
    }

    public boolean lastPromptEstimated() {
        return lastPromptEstimated;
    }

    /** 观测所属会话：子会话的调用会写进同一族台账，展示时得认人。 */
    public String lastPromptSessionId() {
        return lastPromptSessionId;
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
