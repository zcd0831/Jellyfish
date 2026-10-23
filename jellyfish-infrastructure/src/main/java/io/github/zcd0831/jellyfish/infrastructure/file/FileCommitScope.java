package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定按「事实 → 追加 → 替换」执行。
 * 规则出处：模块-应用层 §8.8、模块-基础设施层 §5。
 *
 * <p>S0 只支持 Session 与 RuntimePolicy 的替换；报告 / 反思 / 经验 / 计划随各自切片加入。
 */
public final class FileCommitScope implements CommitScope {

    private final DataDirectory dir;
    private final JsonCodec codec;
    private final List<Object> facts = new ArrayList<>();
    private final List<Record> records = new ArrayList<>();
    private final List<SessionId> recordSessions = new ArrayList<>();
    private final List<Object> replacements = new ArrayList<>();

    public FileCommitScope(DataDirectory dir, JsonCodec codec) {
        this.dir = dir;
        this.codec = codec;
    }

    @Override
    public void stageFact(Object fact) {
        facts.add(fact);
    }

    @Override
    public void stageAppend(SessionId sessionId, Record record) {
        recordSessions.add(sessionId);
        records.add(record);
    }

    @Override
    public void stageReplace(Object aggregate) {
        replacements.add(aggregate);
    }

    @Override
    public void commit() {
        // 顺序即语义：事实先落，状态后落；任何崩溃点都停在"事实已存在、状态未推进"
        for (Object fact : facts) {
            writeFact(fact);
        }
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            SessionId sid = recordSessions.get(i);
            AtomicFiles.append(dir.sessionDir(sid.getValue()).resolve("records-0001.jsonl"), codec.write(toLine(r)));
        }
        for (Object aggregate : replacements) {
            writeAggregate(aggregate);
        }
        facts.clear();
        records.clear();
        recordSessions.clear();
        replacements.clear();
    }

    private void writeFact(Object fact) {
        throw new UnsupportedOperationException("S0 只支持 Session / RuntimePolicy，事实类型待各自切片加入："
                + fact.getClass().getName());
    }

    private void writeAggregate(Object aggregate) {
        if (aggregate instanceof Session) {
            Session s = (Session) aggregate;
            AtomicFiles.write(dir.sessionDir(s.id().getValue()).resolve("meta.json"), codec.write(toMeta(s)));
            return;
        }
        if (aggregate instanceof RuntimePolicy) {
            AtomicFiles.write(dir.settingsFile(), codec.write(aggregate));
            return;
        }
        throw new UnsupportedOperationException("unsupported aggregate: " + aggregate.getClass().getName());
    }

    private static SessionMetaDto toMeta(Session s) {
        SessionMetaDto d = new SessionMetaDto();
        d.id = s.id();
        d.parentId = s.parentSessionId();
        d.agentId = s.agentId();
        d.modelId = s.activeModelId();
        d.status = s.status();
        d.title = s.title();
        d.createdDate = s.createdDate();
        return d;
    }

    private static RecordLineDto toLine(Record r) {
        RecordLineDto d = new RecordLineDto();
        d.id = r.id();
        d.role = r.role();
        d.content = r.content();
        d.toolPayload = r.toolPayload();
        d.sequence = r.sequence();
        d.date = r.date();
        return d;
    }
}
