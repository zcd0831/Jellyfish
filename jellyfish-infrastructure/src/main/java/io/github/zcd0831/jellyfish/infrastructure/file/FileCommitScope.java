package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
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

    /**
     * 事务状态按线程隔离：主循环与各子代理可能并行写不同会话目录，
     * 共享一份 staging 会让一个线程的提交带上另一个线程的写入（D05.1 的"每会话一个写者"）。
     */
    private final ThreadLocal<Staging> staging = new ThreadLocal<Staging>() {
        @Override
        protected Staging initialValue() {
            return new Staging();
        }
    };

    private final DataDirectory dir;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;

    public FileCommitScope(DataDirectory dir, JsonCodec codec) {
        this.dir = dir;
        this.sessionDirs = new SessionDirs(dir);
        this.codec = codec;
    }

    @Override
    public void stageFact(Object fact) {
        staging.get().facts.add(fact);
    }

    @Override
    public void stageAppend(SessionId sessionId, Record record) {
        staging.get().recordSessions.add(sessionId);
        staging.get().records.add(record);
    }

    @Override
    public void stageReplace(Object aggregate) {
        staging.get().replacements.add(aggregate);
    }

    @Override
    public void commit() {
        Staging current = staging.get();
        try {
            // 顺序即语义：事实先落，状态后落；任何崩溃点都停在"事实已存在、状态未推进"
            for (Object fact : current.facts) {
                writeFact(fact);
            }
            for (int i = 0; i < current.records.size(); i++) {
                AtomicFiles.append(sessionDirOf(current.recordSessions.get(i))
                        .resolve("records-0001.jsonl"), codec.write(toLine(current.records.get(i))));
            }
            for (Object aggregate : current.replacements) {
                writeAggregate(aggregate);
            }
        } finally {
            current.clear();
        }
    }

    private static final class Staging {

        private final List<Object> facts = new ArrayList<Object>();
        private final List<Record> records = new ArrayList<Record>();
        private final List<SessionId> recordSessions = new ArrayList<SessionId>();
        private final List<Object> replacements = new ArrayList<Object>();

        void clear() {
            facts.clear();
            records.clear();
            recordSessions.clear();
            replacements.clear();
        }
    }

    private void writeFact(Object fact) {
        if (fact instanceof VerificationReport) {
            new FileReportRepository(dir, codec).save((VerificationReport) fact);
            return;
        }
        if (fact instanceof Reflection) {
            new FileReflectionRepository(dir, codec).save((Reflection) fact);
            return;
        }
        throw new UnsupportedOperationException("暂不支持的事实类型：" + fact.getClass().getName());
    }

    private void writeAggregate(Object aggregate) {
        if (aggregate instanceof Session) {
            Session s = (Session) aggregate;
            AtomicFiles.write(sessionMetaFile(s), codec.write(SessionMetaMapper.toDto(s)));
            return;
        }
        if (aggregate instanceof RuntimePolicy) {
            AtomicFiles.write(dir.settingsFile(),
                    codec.write(FilePolicyRepository.PolicyDto.from((RuntimePolicy) aggregate)));
            return;
        }
        if (aggregate instanceof Plan) {
            Plan plan = (Plan) aggregate;
            AtomicFiles.write(sessionDirOf(plan.sessionId())
                            .resolve("plans").resolve(plan.id().getValue() + ".json"),
                    codec.write(PlanDto.from(plan)));
            return;
        }
        if (aggregate instanceof Experience) {
            Experience experience = (Experience) aggregate;
            AtomicFiles.write(dir.experience().resolve(experience.id().getValue() + ".json"),
                    codec.write(FileExperienceRepository.ExperienceDto.from(experience)));
            return;
        }
        if (aggregate instanceof Budget) {
            Budget budget = (Budget) aggregate;
            AtomicFiles.write(sessionDirOf(budget.sessionFamilyRootId()).resolve("budget.json"),
                    codec.write(toBudget(budget)));
            return;
        }
        throw new UnsupportedOperationException("unsupported aggregate: " + aggregate.getClass().getName());
    }

    /**
     * 会话目录：子会话在 sub/ 下（深度恒为 1）；新会话的目录还没建时按顶层会话建出来。
     * 规则出处：技术决策 D05.1。
     */
    private java.nio.file.Path sessionDirOf(SessionId sessionId) {
        try {
            return sessionDirs.of(sessionId.getValue());
        } catch (IllegalArgumentException notCreatedYet) {
            return dir.sessionDir(sessionId.getValue());
        }
    }

    /** 子会话在 sub/ 下，深度恒为 1（D05.1）。 */
    private java.nio.file.Path sessionMetaFile(Session session) {
        if (session.parentSessionId() == null) {
            return dir.sessionDir(session.id().getValue()).resolve("meta.json");
        }
        return dir.sessionDir(session.parentSessionId().getValue())
                .resolve("sub").resolve(session.id().getValue()).resolve("meta.json");
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
