package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;

/**
 * 一轮用户输入：T1 追加用户消息 → 外循环 → 会话落回 RESUMABLE。
 * 规则出处：模块-应用层 §8.1、§9；技术决策 D06（取消 / 断流不落 ASSISTANT）。
 */
public final class RunSessionUseCase {

    private final SessionRepository sessions;
    private final RecordStore records;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final ExecutionLoop executionLoop;

    public RunSessionUseCase(SessionRepository sessions, RecordStore records, CommitScope commitScope,
                             DomainEventPublisher eventPublisher, ExecutionLoop executionLoop) {
        this.sessions = sessions;
        this.records = records;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.executionLoop = executionLoop;
    }

    public Result<RunSessionResult> run(SessionId sessionId, String text, ChunkSink sink) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        if (session.status() == SessionStatus.ARCHIVED) {
            return Result.fail(UseCaseError.of(SessionError.ARCHIVED, "会话已归档", sessionId));
        }
        session.hydrateRecords(records.last(sessionId, Integer.MAX_VALUE));
        session.resume();
        if (text != null) {
            // 续跑（text == null）不追加用户消息：会话开启时首条输入已落盘（§8.3）
            Record userRecord = session.appendRecord(RecordRole.USER, text, null, System.currentTimeMillis());
            commitScope.stageAppend(sessionId, userRecord);
        }
        commitScope.stageReplace(session);
        commitScope.commit();

        try {
            ExecutionLoop.Outcome outcome = executionLoop.run(session, sink);
            finish(session);
            return Result.ok(RunSessionResult.of(map(outcome)));
        } catch (java.util.concurrent.CancellationException cancelled) {
            // 中断不是错误：会话回到可续跑，半截输出不落盘（D06-4、D11）
            finish(session);
            return Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CANCELLED));
        } catch (ModelInvocationException unavailable) {
            // 模型不可用：不改任何状态（模块-应用层 §9）
            return Result.fail(UseCaseError.of(unavailable.error(), unavailable.getMessage(), sessionId));
        }
    }

    private static RunSessionResult.Outcome map(ExecutionLoop.Outcome outcome) {
        switch (outcome) {
            case CONVERGED:
                return RunSessionResult.Outcome.CONVERGED;
            case SUSPENDED:
                return RunSessionResult.Outcome.SUSPENDED;
            default:
                return RunSessionResult.Outcome.ROUNDS_EXHAUSTED;
        }
    }

    private void finish(Session session) {
        session.markResumable();
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
    }
}
