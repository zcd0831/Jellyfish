package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 并行发起子会话：各自独立超时（<strong>从各自实际开始运行计时</strong>）、join-all、预算归集到族根。
 * <p>超时 = 终止该子代理（主会话继续）：终止走与会话相同的令牌，边界到模型调用与工具执行，
 * 不是线程中断、不是把结果就地丢掉（08-application §8.9.4.1、05-action §5.9.1）。
 * <p>`sub/` 目录与流水由 `StartSessionUseCase` 负责；本类只调度。
 * 规则出处：05-action §5.9.1（spawn_subagents 展开）。
 */
public final class SubagentLauncher {

    /** 未开始的任务没有 deadline：按这个步长探测，避免父线程空转。 */
    private static final long PROBE_MILLIS = 50L;

    private final StartSessionUseCase startSession;
    private final RunSessionProvider runSession;
    private final SessionRepository sessions;
    private final RecordStore records;
    private final BudgetService budgetService;
    private final PolicyRepository policies;
    private final ProgressPort progress;
    private final ExecutorService pool;
    private final SessionCancellations cancellations;

    public SubagentLauncher(StartSessionUseCase startSession, RunSessionProvider runSession,
                            SessionRepository sessions, RecordStore records, BudgetService budgetService,
                            PolicyRepository policies, ProgressPort progress, ExecutorService pool,
                            SessionCancellations cancellations) {
        this.startSession = startSession;
        this.runSession = runSession;
        this.sessions = sessions;
        this.records = records;
        this.budgetService = budgetService;
        this.policies = policies;
        this.progress = progress;
        this.pool = pool;
        this.cancellations = cancellations;
    }

    public List<SubagentResult> launch(Session parent, List<SubagentRequest> requests) {
        int timeoutMillis = policies.load().subagentExecutionTimeoutMillis();
        progress.emit(ProgressEvent.of(ProgressEvent.Kind.SUBAGENTS_RUNNING,
                "并行启动 " + requests.size() + " 个子代理"));

        CompletionService<SubagentResult> completion = new ExecutorCompletionService<SubagentResult>(pool);
        Map<Future<SubagentResult>, SubagentTask> byFuture =
                new IdentityHashMap<Future<SubagentResult>, SubagentTask>();
        List<SubagentTask> tasks = new ArrayList<SubagentTask>();
        for (int i = 0; i < requests.size(); i++) {
            SubagentTask task = new SubagentTask(parent, requests.get(i), i, timeoutMillis,
                    cancellations.beginChild(parent.id()));
            tasks.add(task);
            byFuture.put(completion.submit(task), task);
        }

        List<SubagentResult> results = new ArrayList<SubagentResult>(
                java.util.Collections.<SubagentResult>nCopies(tasks.size(), null));
        try {
            while (unsettled(tasks) > 0) {
                // join-all：等「最早到期」或「任意一个完成」，不是按提交顺序各等一份超时
                long wait = terminateExpired(tasks, results);
                if (unsettled(tasks) == 0) {
                    break;
                }
                Future<SubagentResult> done = completion.poll(wait, TimeUnit.MILLISECONDS);
                if (done == null) {
                    continue;   // 只是到点了：下一轮把到期者终止掉
                }
                SubagentTask task = byFuture.get(done);
                if (task.settle()) {
                    results.set(task.index(), collect(done));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminateAll(tasks);
        }
        return fillMissing(results);
    }

    /** 到期就终止该子代理，返回下一步最多等多久（毫秒）。 */
    private long terminateExpired(List<SubagentTask> tasks, List<SubagentResult> results) {
        long now = System.currentTimeMillis();
        long earliest = -1L;
        for (SubagentTask task : tasks) {
            if (task.isSettled()) {
                continue;
            }
            long deadline = task.deadline();
            if (deadline == 0L) {
                earliest = pickEarlier(earliest, PROBE_MILLIS);
                continue;
            }
            long left = deadline - now;
            if (left > 0) {
                earliest = pickEarlier(earliest, left);
                continue;
            }
            // 终止边界与会话终止相同：令牌下去，在途模型调用与工具随之收手
            if (task.settle()) {
                // 先填结果再取消：取消会让子会话马上收手，父线程不必等它。
                // 取消的是「该子代理自己的令牌」——会话还没建出来时也照样生效（beginChild 已经挂上）
                SessionId childId = task.childId() != null
                        ? task.childId() : cancellations.sessionOf(task.signal());
                results.set(task.index(), SubagentResult.of(childId, TIMED_OUT));
                cancellations.cancelSessionOrSignal(childId, task.signal());
            }
        }
        return earliest < 0 ? PROBE_MILLIS : earliest;
    }

    private static long pickEarlier(long current, long candidate) {
        return current < 0 ? candidate : Math.min(current, candidate);
    }

    private static int unsettled(List<SubagentTask> tasks) {
        int count = 0;
        for (SubagentTask task : tasks) {
            if (!task.isSettled()) {
                count++;
            }
        }
        return count;
    }

    private void terminateAll(List<SubagentTask> tasks) {
        for (SubagentTask task : tasks) {
            if (task.settle()) {
                cancellations.cancelSessionOrSignal(task.childId(), task.signal());
            }
        }
    }

    /** 结果按请求顺序回给模型：并行是执行方式，不是结果顺序。 */
    private static List<SubagentResult> fillMissing(List<SubagentResult> results) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, SubagentResult.of(null, "被中断"));
            }
        }
        return results;
    }

    private SubagentResult collect(Future<SubagentResult> done) {
        try {
            return done.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return SubagentResult.of(null, "子代理失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SubagentResult.of(null, "被中断");
        }
    }

    private static final String TIMED_OUT = "已终止（超时）";

    /** 一个子代理的任务：deadline 在真正开始运行的那一刻才确定。 */
    private final class SubagentTask implements Callable<SubagentResult> {

        private final Session parent;
        private final SubagentRequest request;
        private final int index;
        private final int timeoutMillis;
        private final CancellationToken signal;
        private volatile long deadline;
        private volatile SessionId childId;
        private volatile boolean settled;

        SubagentTask(Session parent, SubagentRequest request, int index, int timeoutMillis,
                     CancellationToken signal) {
            this.parent = parent;
            this.request = request;
            this.index = index;
            this.timeoutMillis = timeoutMillis;
            this.signal = signal;
        }

        CancellationToken signal() {
            return signal;
        }

        int index() {
            return index;
        }

        @Override
        public SubagentResult call() {
            // t0 = 本任务真正开始跑的时刻：排队时间不算在它头上，join 顺序也改不了它
            deadline = System.currentTimeMillis() + timeoutMillis;
            return runOne(parent, request);
        }

        long deadline() {
            return deadline;
        }

        SessionId childId() {
            return childId;
        }

        boolean isSettled() {
            return settled;
        }

        /** 谁先拿到 settled 谁负责给这个位置填结果（父线程或中断收尾）。 */
        boolean settle() {
            synchronized (this) {
                if (settled) {
                    return false;
                }
                settled = true;
                return true;
            }
        }

        private SubagentResult runOne(Session parentSession, SubagentRequest request) {
            Result<SessionId> started = startSession.startSubSession(parentSession.id(), request.agentId(),
                    request.modelId(), request.task(), signal);
            if (!started.isOk()) {
                return SubagentResult.of(null, "子会话创建失败：" + started.error().error().code());
            }
            childId = started.value();
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

        private String lastAssistant(SessionId childSessionId) {
            List<Record> recent = records.last(childSessionId, 50);
            for (int i = recent.size() - 1; i >= 0; i--) {
                if (recent.get(i).role() == RecordRole.ASSISTANT) {
                    return recent.get(i).content();
                }
            }
            Session child = sessions.load(childSessionId);
            return child == null || child.title() == null ? "" : child.title();
        }
    }
}
