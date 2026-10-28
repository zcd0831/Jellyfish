package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行发起子会话：各自独立超时（从实际开始计时）、join-all、预算归集到族根。
 * `sub/` 目录与流水由 `StartSessionUseCase` 负责；本类只调度。
 * 规则出处：05-action §5.9.1（spawn_subagents 展开）。
 */
public final class SubagentLauncher {

    private final StartSessionUseCase startSession;
    private final RunSessionProvider runSession;
    private final SessionRepository sessions;
    private final RecordStore records;
    private final BudgetService budgetService;
    private final PolicyRepository policies;
    private final ProgressPort progress;
    private final ExecutorService pool;

    public SubagentLauncher(StartSessionUseCase startSession, RunSessionProvider runSession,
                            SessionRepository sessions, RecordStore records, BudgetService budgetService,
                            PolicyRepository policies, ProgressPort progress, ExecutorService pool) {
        this.startSession = startSession;
        this.runSession = runSession;
        this.sessions = sessions;
        this.records = records;
        this.budgetService = budgetService;
        this.policies = policies;
        this.progress = progress;
        this.pool = pool;
    }

    public List<SubagentResult> launch(Session parent, List<SubagentRequest> requests) {
        int timeoutMillis = policies.load().subagentExecutionTimeoutMillis();
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUBAGENTS_RUNNING,
                "并行启动 " + requests.size() + " 个子代理"));

        List<Future<SubagentResult>> futures = new ArrayList<Future<SubagentResult>>();
        for (SubagentRequest request : requests) {
            futures.add(pool.submit(task(parent, request)));
        }

        List<SubagentResult> results = new ArrayList<SubagentResult>();
        for (Future<SubagentResult> future : futures) {
            results.add(await(future, timeoutMillis));
        }
        return results;
    }

    /** 每个子代理独立超时：一个超时不影响其它（§8.1 要点）。 */
    private SubagentResult await(Future<SubagentResult> future, int timeoutMillis) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return SubagentResult.of(null, "超时未完成");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return SubagentResult.of(null, "子代理失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return SubagentResult.of(null, "被中断");
        }
    }

    private Callable<SubagentResult> task(final Session parent, final SubagentRequest request) {
        return new Callable<SubagentResult>() {
            @Override
            public SubagentResult call() {
                return runOne(parent, request);
            }
        };
    }

    private SubagentResult runOne(Session parent, SubagentRequest request) {
        Result<SessionId> started = startSession.startSubSession(parent.id(), request.agentId(),
                request.modelId(), request.task());
        if (!started.isOk()) {
            return SubagentResult.of(null, "子会话创建失败：" + started.error().error().code());
        }
        SessionId childId = started.value();
        // 子会话的消耗经 parentSessionId 直接记到族根台账（BudgetService.recordUsage），
        // 不再二次归集，避免同一笔消耗被计两次。
        Result<RunSessionResult> run = runSession.get().run(childId, null, ChunkSink.NOOP);
        String conclusion = lastAssistant(childId);
        if (!run.isOk()) {
            return SubagentResult.of(childId, "运行失败：" + run.error().error().code()
                    + (conclusion.isEmpty() ? "" : "；已完成部分：" + conclusion));
        }
        return SubagentResult.of(childId, conclusion);
    }

    private String lastAssistant(SessionId childId) {
        List<Record> recent = records.last(childId, 50);
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (recent.get(i).role() == RecordRole.ASSISTANT) {
                return recent.get(i).content();
            }
        }
        Session child = sessions.load(childId);
        return child == null || child.title() == null ? "" : child.title();
    }
}
