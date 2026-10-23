package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 会话聚合根。规则出处：模块-感知 §3.1、§7.1、§8.5。 */
public class Session {

    private final SessionId id;
    private final AgentId agentId;
    private final SessionId parentSessionId;
    private final long createdDate;
    private final SystemPromptSnapshot systemPromptSnapshot;
    private final List<Record> records;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private ModelId activeModelId;
    private SessionStatus status;
    private String title;

    private Session(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId activeModelId,
                    SessionStatus status, String title, long createdDate, List<Record> records,
                    SystemPromptSnapshot snapshot) {
        this.id = id;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.activeModelId = activeModelId;
        this.status = status;
        this.title = title;
        this.createdDate = createdDate;
        this.records = records;
        this.systemPromptSnapshot = snapshot;
    }

    /** 会话开启 / 子代理派遣的唯一创建路径（应用层调用）。规则出处：模块-感知 §4、§7.1 规则 1。 */
    public static Session create(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId modelId,
                                 long now, SystemPromptSnapshot snapshot) {
        return new Session(id, parentSessionId, agentId, modelId, SessionStatus.RUNNING, null, now,
                new ArrayList<Record>(), snapshot);
    }

    /** 会话续跑：从持久化形态重建，不重新派发事件。规则出处：模块-感知 §7.1 规则 11。 */
    public static Session rehydrate(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId modelId,
                                    SessionStatus status, String title, long createdDate,
                                    List<Record> records, SystemPromptSnapshot snapshot) {
        return new Session(id, parentSessionId, agentId, modelId, status, title, createdDate,
                new ArrayList<Record>(records), snapshot);
    }

    /** 规则出处：模块-感知 §7.1 规则 5（只追加）。 */
    public Record appendRecord(RecordRole role, String content, ToolResultPayload payload, long now) {
        Record record = new Record(RecordId.of(java.util.UUID.randomUUID().toString()), role, content,
                payload, nextSequence(), now);
        records.add(record);
        return record;
    }

    private int nextSequence() {
        return records.isEmpty() ? 1 : records.get(records.size() - 1).sequence() + 1;
    }

    /**
     * 三步推导 + 一次降级。规则出处：模块-感知 §7.1 规则 9、§8.5。
     * 系统提示词不在这里：它来自快照，由调用方单独传给模型（§7.1 规则 13）。
     */
    public ContextWindow buildContextForLlm(ContextWindowPolicy policy, TokenEstimator estimator, int inputCeiling) {
        List<Record> system = selectByRole(RecordRole.SYSTEM);
        Record latestSummary = latest(RecordRole.SUMMARY);
        List<Record> recent = recent(policy.getRecordCount());

        int systemTokens = tokensOf(system, estimator);
        int summaryTokens = latestSummary == null ? 0 : estimator.estimate(latestSummary.content());
        int windowTokens = tokensOf(recent, estimator);
        int usedTokens = systemTokens + summaryTokens + windowTokens;

        List<Record> windowRecords;
        if (usedTokens > 0 && (double) systemTokens / usedTokens > policy.getSystemRatioLimit()) {
            // 降级只保留系统提示词快照：SYSTEM 注入整段丢弃，注入记录不再进入消息
            windowRecords = withoutSystemRecords(recent);
            usedTokens = summaryTokens + windowTokens;
            systemTokens = 0;
        } else {
            windowRecords = merge(system, latestSummary, recent);
        }

        ContextUsage usage = new ContextUsage(usedTokens, inputCeiling, systemTokens, summaryTokens,
                windowRecords.size());
        return new ContextWindow(windowRecords, usage);
    }

    /** 规则出处：模块-感知 §8.2 第 3 步、§7.1 规则 9 ③。 */
    public void markSummarized(String summary, long now) {
        appendRecord(RecordRole.SUMMARY, summary, null, now);
        pendingEvents.add(new SummarizationCompleted());
    }

    /** 只有降级会切模型；Agent 不可切换。规则出处：模块-感知 §7.1 规则 2、§6。 */
    public void switchModel(ModelId modelId) {
        if (modelId.equals(activeModelId)) {
            return;
        }
        this.activeModelId = modelId;
        pendingEvents.add(new ModelSwitched());
    }

    /** 规则出处：模块-感知 §7.1 规则 6（父取消级联子由应用层执行）。 */
    public void cancel() {
        if (status == SessionStatus.ARCHIVED) {
            throw new DomainInvariantViolation("已归档的会话不能取消：" + id);
        }
        pendingEvents.add(new SessionCancelled());
    }

    /** 规则出处：模块-感知 §7.1 规则 7。 */
    public void archive() {
        this.status = SessionStatus.ARCHIVED;
    }

    /** 运行态消失不是状态迁移：进程退出时由应用层把 RUNNING 落为 RESUMABLE。 */
    public void markResumable() {
        if (status == SessionStatus.RUNNING) {
            this.status = SessionStatus.RESUMABLE;
        }
    }

    public void resume() {
        if (status == SessionStatus.ARCHIVED) {
            throw new DomainInvariantViolation("已归档的会话不能续跑：" + id);
        }
        this.status = SessionStatus.RUNNING;
    }

    public void retitle(String title) {
        this.title = title;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    private List<Record> selectByRole(RecordRole role) {
        List<Record> out = new ArrayList<>();
        for (Record r : records) {
            if (r.role() == role) {
                out.add(r);
            }
        }
        return out;
    }

    private Record latest(RecordRole role) {
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).role() == role) {
                return records.get(i);
            }
        }
        return null;
    }

    /**
     * 最近 n 条"对话"记录：SYSTEM 注入不参与淘汰（规则 10），SUMMARY 已由第 ② 步单独取最新一条，
     * 旧的摘要不再进窗口——否则折叠只是往窗口里堆摘要。
     */
    private List<Record> recent(int count) {
        List<Record> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < count; i--) {
            RecordRole role = records.get(i).role();
            if (role != RecordRole.SYSTEM && role != RecordRole.SUMMARY) {
                out.add(0, records.get(i));
            }
        }
        return out;
    }

    private static List<Record> merge(List<Record> system, Record summary, List<Record> recent) {
        Set<Record> merged = new LinkedHashSet<>();
        merged.addAll(system);
        if (summary != null) {
            merged.add(summary);
        }
        merged.addAll(recent);
        return new ArrayList<>(merged);
    }

    private static List<Record> withoutSystemRecords(List<Record> recent) {
        return new ArrayList<>(recent);
    }

    private static int tokensOf(List<Record> records, TokenEstimator estimator) {
        int total = 0;
        for (Record r : records) {
            total += estimator.estimate(r.content());
        }
        return total;
    }

    public SessionId id() {
        return id;
    }

    public SessionId parentSessionId() {
        return parentSessionId;
    }

    public AgentId agentId() {
        return agentId;
    }

    public ModelId activeModelId() {
        return activeModelId;
    }

    public SessionStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public long createdDate() {
        return createdDate;
    }

    /** 只读视图：调用方不得修改流水（§7.1 规则 5）。 */
    public List<Record> records() {
        return java.util.Collections.unmodifiableList(records);
    }

    public SystemPromptSnapshot systemPromptSnapshot() {
        return systemPromptSnapshot;
    }
}
