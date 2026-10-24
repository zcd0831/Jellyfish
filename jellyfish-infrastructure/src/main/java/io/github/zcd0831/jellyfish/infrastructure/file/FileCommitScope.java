package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
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
 * <p>已支持 Session / RuntimePolicy / Budget 的替换；报告 / 反思 / 经验 / 计划随各自切片加入。
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

    /** 事实只新建、不覆盖：报告一经写出就是不可变的（§7.2）。 */
    private void writeFact(Object fact) {
        if (fact instanceof VerificationReport) {
            new FileReportRepository(dir, codec).save((VerificationReport) fact);
            return;
        }
        throw new UnsupportedOperationException("暂不支持的事实类型：" + fact.getClass().getName());
    }

    private void writeAggregate(Object aggregate) {
        if (aggregate instanceof Session) {
            Session s = (Session) aggregate;
            AtomicFiles.write(dir.sessionDir(s.id().getValue()).resolve("meta.json"),
                    codec.write(SessionMetaMapper.toDto(s)));
            return;
        }
        if (aggregate instanceof RuntimePolicy) {
            AtomicFiles.write(dir.settingsFile(), codec.write(aggregate));
            return;
        }
        if (aggregate instanceof Plan) {
            Plan plan = (Plan) aggregate;
            AtomicFiles.write(dir.sessionDir(plan.sessionId().getValue())
                            .resolve("plans").resolve(plan.id().getValue() + ".json"),
                    codec.write(PlanDto.from(plan)));
            return;
        }
        if (aggregate instanceof Budget) {
            Budget budget = (Budget) aggregate;
            AtomicFiles.write(dir.sessionDir(budget.sessionFamilyRootId().getValue()).resolve("budget.json"),
                    codec.write(toBudget(budget)));
            return;
        }
        throw new UnsupportedOperationException("unsupported aggregate: " + aggregate.getClass().getName());
    }

    private static FileBudgetRepository.BudgetDto toBudget(Budget budget) {
        FileBudgetRepository.BudgetDto dto = new FileBudgetRepository.BudgetDto();
        dto.id = budget.id().getValue();
        dto.sessionFamilyRootId = budget.sessionFamilyRootId().getValue();
        dto.consumedTokens = budget.consumedTokens();
        dto.warned = budget.warned();
        return dto;
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
