package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

/**
 * 取消 / 归档 / 删除。归档与删除不可 resume，删除保留经验库。
 * 规则出处：模块-应用层 §8.4、模块-感知 §7.1 规则 7/8。
 */
public final class ManageSessionUseCase {

    private final SessionRepository sessions;
    private final ArtifactStore artifacts;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final RecordStore records;
    private final ContextWindowService contextWindow;

    public ManageSessionUseCase(SessionRepository sessions, ArtifactStore artifacts, CommitScope commitScope,
                                DomainEventPublisher eventPublisher, RecordStore records,
                                ContextWindowService contextWindow) {
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.records = records;
        this.contextWindow = contextWindow;
    }

    /**
     * 取消：父取消级联子会话。运行中的循环由外壳另行打断（取消令牌），这里只落事实。
     * 规则出处：模块-感知 §7.1 规则 6、模块-应用层 §8.4。
     */
    public Result<Void> cancel(SessionId sessionId) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
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

    /** 规则出处：模块-应用层 §8.4（压成 archive/<id>.zip 后删除原目录；此后应用不再认识它，D22）。 */
    public Result<Void> archive(SessionId sessionId) {
        if (sessions.load(sessionId) == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        sessions.archive(sessionId);
        return Result.ok(null);
    }

    /** 规则出处：模块-应用层 §8.4（递归删除会话族，经验与技能不受影响）。 */
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
     * 规则出处：模块-应用层 §8.4（查看/回放）。
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
        return Result.ok(new SessionTranscript(session.id(), session.title(), session.activeModelId(),
                session.createdDate(), new ArrayList<Record>(shown), hasEarlier, contextWindow.contextLabel(session)));
    }

    /** 列出可用会话（含子会话），界面据此让人挑一个加载（D22）。 */
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
