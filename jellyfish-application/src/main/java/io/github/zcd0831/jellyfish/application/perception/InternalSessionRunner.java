package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.ReActLoop;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.Collections;

/**
 * fork 型调用的唯一执行点：建内部子会话 → 复制父会话生效窗口 → 末尾追加指令 → 只跑一次内循环。
 * 工具白名单为空、工具快照沿用父会话（否则缓存前缀就变了），产物是子会话最后一条 assistant 文本。
 * 规则出处：08-application §8.9.7、§8.6.1。
 */
public class InternalSessionRunner {

    private final StartSessionUseCase startSession;
    private final SessionRepository sessions;
    private final ReActLoop reActLoop;
    private final SessionToolScope scope;
    private final CommitScope commitScope;
    private final Clock clock;
    private final SessionCancellations cancellations;
    private final BudgetService budgetService;

    public InternalSessionRunner(StartSessionUseCase startSession, SessionRepository sessions, ReActLoop reActLoop,
                                 SessionToolScope scope, CommitScope commitScope, Clock clock,
                                 SessionCancellations cancellations, BudgetService budgetService) {
        this.startSession = startSession;
        this.sessions = sessions;
        this.reActLoop = reActLoop;
        this.scope = scope;
        this.commitScope = commitScope;
        this.clock = clock;
        this.cancellations = cancellations;
        this.budgetService = budgetService;
    }

    /**
     * 返回子会话最后一条 assistant 文本；模型不可用/取消上抛，由调用方按环境故障处理。
     * <p>终止与“没产出”分开：fork 没建起来时，父已被终止就抛 {@link java.util.concurrent.CancellationException}
     * （终止是终态，§8.9.4.1），否则只是环境问题、本次不产出。
     * 非 final：装配级测试用它替换执行点（真跑一次内循环要拖进整套端口）。
     * 规则出处：08-application §8.9.4.1、§8.9.7。
     */
    public String run(Session parent, String instruction) {
        if (parent == null) {
            return null;
        }
        Result<SessionId> forked = startSession.forkInternal(parent.id());
        if (!forked.isOk()) {
            cancellations.forSession(parent.id()).throwIfCancelled();
            return null;   // 父会话不在：本次不产出，不建子会话
        }
        SessionId childId = forked.value();
        try {
            return runInChild(parent, childId, instruction);
        } finally {
            // 用量一次性归集到族根：子会话不写自己的台账（05-action §5.8.5、08-application §8.9.7）
            budgetService.settleFromSubSession(childId);
            // 一次性子会话：结束即注销，否则长会话里登记表只增不减（08-application §8.9.4.1）
            scope.clear(childId);
            cancellations.unregister(childId);
        }
    }

    private String runInChild(Session parent, SessionId childId, String instruction) {
        Session child = sessions.load(childId);
        copyWindow(parent, child);
        Record instructionRecord = child.appendRecord(RecordRole.USER, instruction, null, clock.nowMillis());
        commitScope.stageAppend(childId, instructionRecord);
        commitScope.commit();

        scope.restrict(childId, Collections.<io.github.zcd0831.jellyfish.domain.shared.ToolId>emptySet());
        reActLoop.runIteration(child, null, ChunkSink.NOOP);
        return lastAssistant(child);
    }

    /**
     * 父会话的生效窗口原样复制：SUMMARY 覆盖已复制的全部记录，其余按原角色与 turnId 重放。
     * 复制的是“模型看到的那份”，所以子会话重建出的 messages 与父请求逐字节一致。
     */
    private void copyWindow(Session parent, Session child) {
        for (Record record : new ContextWindowOf(parent).records()) {
            if (record.role() == RecordRole.SUMMARY) {
                child.markSummarized(record.content(), 0, clock.nowMillis());
                continue;
            }
            child.appendRecord(record.role(), record.content(), record.reasoning(), record.turnId(),
                    record.producerModelId(), record.toolPayload(), clock.nowMillis());
        }
    }

    private static String lastAssistant(Session child) {
        Record found = null;
        for (Record record : child.records()) {
            if (record.role() == RecordRole.ASSISTANT) {
                found = record;
            }
        }
        return found == null ? null : found.content();
    }

    /** 只取窗口，不复制整条流水：父会话的旧记录不进子会话。 */
    private static final class ContextWindowOf {

        private final java.util.List<Record> records;

        ContextWindowOf(Session parent) {
            this.records = parent.buildContextForLlm(text -> 0, Integer.MAX_VALUE).getRecords();
        }

        java.util.List<Record> records() {
            return records;
        }
    }
}
