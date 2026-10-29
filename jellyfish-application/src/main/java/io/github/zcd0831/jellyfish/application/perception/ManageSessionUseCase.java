package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

/**
 * 取消 / 归档 / 删除。归档与删除不可 resume，删除保留经验库。
 * 规则出处：08-application §8.9.4、04-perception §4.8.1 规则 7/8。
 */
public final class ManageSessionUseCase {

    private final SessionRepository sessions;
    private final ArtifactStore artifacts;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final RecordStore records;
    private final ContextWindowService contextWindow;
    private final BudgetRepository budgets;
    private final PolicyRepository policies;
    private final SessionCancellations cancellations;

    public ManageSessionUseCase(SessionRepository sessions, ArtifactStore artifacts, CommitScope commitScope,
                                DomainEventPublisher eventPublisher, RecordStore records,
                                ContextWindowService contextWindow, BudgetRepository budgets,
                                PolicyRepository policies, SessionCancellations cancellations) {
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.records = records;
        this.contextWindow = contextWindow;
        this.budgets = budgets;
        this.policies = policies;
        this.cancellations = cancellations;
    }

    /**
     * 终止：先按会话发终止信号（在途模型调用与工具随之收手、子会话级联），再落事实。
     * 幂等：不在跑的会话拿不到令牌，只落事件。
     * 规则出处：04-perception §4.8.1 规则 6、08-application §8.9.4 / §8.9.4.1。
     */
    public Result<Void> cancel(SessionId sessionId) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        cancellations.cancel(sessionId);
        session.cancel();
        commitScope.stageReplace(session);
        for (SessionId child : childSessionsOf(sessionId)) {
            Session childSession = sessions.load(child);
            if (childSession != null) {
                childSession.cancel();
                commitScope.stageReplace(childSession);
            }
        }
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
        return Result.ok(null);
    }

    /** 规则出处：08-application §8.9.4。 */
    public Result<Void> archive(SessionId sessionId) {
        if (sessions.load(sessionId) == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        sessions.archive(sessionId);
        return Result.ok(null);
    }

    /** 规则出处：08-application §8.9.4（递归删除会话族，经验与技能不受影响）。 */
    public Result<Void> delete(SessionId sessionId) {
        if (sessions.load(sessionId) == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        artifacts.deleteSession(sessionId);
        sessions.delete(sessionId);
        return Result.ok(null);
    }

    /**
     * 回放：会话头 + 最近 limit 条记录（只读，不动任何状态）。
     * 规则出处：08-application §8.9.4（查看/回放）。
     */
    public Result<SessionTranscript> transcript(SessionId sessionId, int limit) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        int wanted = Math.max(1, limit);
        List<Record> tail = records.last(sessionId, wanted + 1);
        boolean hasEarlier = tail.size() > wanted;
        List<Record> shown = hasEarlier ? tail.subList(tail.size() - wanted, tail.size()) : tail;
        Budget budget = budgets.load(rootOf(session));
        return Result.ok(new SessionTranscript(session.id(), session.title(), session.activeModelId(),
                session.createdDate(), new ArrayList<Record>(shown), hasEarlier, contextWindow.contextLabel(session),
                budget == null ? 0L : budget.consumedTokens(),
                policies.load().budgetLimitTokens()));
    }

    /** 台账在会话族根上：子会话往上找根（与 BudgetService 同一规则）。 */
    private SessionId rootOf(Session session) {
        SessionId current = session.id();
        SessionId parent = session.parentSessionId();
        int guard = 0;
        while (parent != null && guard++ < 16) {
            current = parent;
            Session look = sessions.load(parent);
            parent = look == null ? null : look.parentSessionId();
        }
        return current;
    }

    /** 列出可用会话（含子会话），界面据此让人挑一个加载。 */
    public Result<List<SessionSummary>> list() {
        return Result.ok(sessions.list());
    }

    private List<SessionId> childSessionsOf(SessionId parentId) {
        List<SessionId> children = new ArrayList<SessionId>();
        for (SessionSummary candidate : sessions.list()) {
            if (parentId.equals(candidate.parentId())) {
                children.add(candidate.id());
            }
        }
        return children;
    }
}
