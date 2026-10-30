package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.ToolInvoker;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;

/**
 * 一轮用户输入：T1 追加用户消息 → 外循环 → 会话落回 RESUMABLE。
 * 规则出处：08-application §8.9.1、§8.10。
 */
public final class RunSessionUseCase {

    private final SessionRepository sessions;
    private final RecordStore records;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;
    private final SessionRunner runner;
    private final ModelInvocationPort model;
    private final ToolInvoker tools;
    private final HumanInteractionPort human;
    private final ProgressPort progress;
    private final PlanRepository plans;
    private final ContextWindowService contextWindow;
    private final SessionCancellations cancellations;

    public RunSessionUseCase(SessionRepository sessions, RecordStore records, CommitScope commitScope,
                             DomainEventPublisher eventPublisher, SessionRunner runner,
                             ModelInvocationPort model, ToolInvoker tools, HumanInteractionPort human,
                             ProgressPort progress, PlanRepository plans, ContextWindowService contextWindow,
                             SessionCancellations cancellations) {
        this.sessions = sessions;
        this.records = records;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
        this.runner = runner;
        this.model = model;
        this.tools = tools;
        this.human = human;
        this.progress = progress;
        this.plans = plans;
        this.contextWindow = contextWindow;
        this.cancellations = cancellations;
    }

    /** 没有令牌的调用（测试复跑、内部复用）按"永不取消"处理（§8.4.1）。 */
    public Result<RunSessionResult> run(SessionId sessionId, String text, ChunkSink sink) {
        return run(sessionId, text, sink, CancellationToken.never());
    }

    public Result<RunSessionResult> run(SessionId sessionId, String text, ChunkSink sink, CancellationToken token) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        // 终止信号按会话登记：外部令牌（外壳）只是同一信号的另一个触发器，
        // 这样模型调用与工具执行都能按 sessionId 取到它（08-application §8.9.4.1）
        final CancellationToken termination = cancellations.registerIfAbsent(sessionId, session.parentSessionId());
        if (token != null && token != termination) {
            token.onCancel(new Runnable() {
                @Override
                public void run() {
                    cancellations.cancel(sessionId);
                }
            });
        }
        try {
            return execute(session, sessionId, text, sink, termination);
        } finally {
            cancellations.unregister(sessionId);
        }
    }

    private Result<RunSessionResult> execute(Session session, SessionId sessionId, String text, ChunkSink sink,
                                             CancellationToken token) {
        session.hydrateRecords(records.last(sessionId, Integer.MAX_VALUE));
        if (text != null) {
            // 续跑（text == null）不追加用户消息：会话开启时首条输入已落盘（08-application §8.9.3）
            Record userRecord = session.appendRecord(RecordRole.USER, text, null, System.currentTimeMillis());
            commitScope.stageAppend(sessionId, userRecord);
        } else {
            // 应用事件 SessionResumed：续跑无状态转换，只给界面看（04-perception §4.7、08-application §8.9.3）
            progress.emit(ProgressEvent.of(ProgressEvent.Kind.SESSION_RESUMED, "续跑：" + sessionId));
        }
        commitScope.stageReplace(session);
        commitScope.commit();

        try {
            RunOutcome outcome = runner.run(session, sink, token, context(session, sink, token));
            finish(session);
            return Result.ok(RunSessionResult.of(map(outcome)));
        } catch (java.util.concurrent.CancellationException cancelled) {
            // 终止不是错误：会话回到可续跑，半截输出不落盘（08-application §8.9.4.1）
            finish(session);
            return Result.ok(RunSessionResult.of(RunSessionResult.Outcome.CANCELLED));
        } catch (ModelInvocationException unavailable) {
            // 模型不可用：不改任何状态（08-application §8.10）
            return Result.fail(UseCaseError.of(unavailable.error(), unavailable.getMessage(), sessionId));
        }
    }

    /** 交给扩展点的扶手：插件换掉循环时也能拿到同一套能力。 */
    private RunContext context(Session session, ChunkSink sink, CancellationToken token) {
        return new RunContext(session, sink, token, model, tools, human, progress, plans, contextWindow);
    }

    private static RunSessionResult.Outcome map(RunOutcome outcome) {
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
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
    }
}
