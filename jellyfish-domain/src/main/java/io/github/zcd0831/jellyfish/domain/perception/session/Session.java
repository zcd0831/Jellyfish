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
    private String title;

    private Session(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId activeModelId,
                    String title, long createdDate, List<Record> records,
                    SystemPromptSnapshot snapshot) {
        this.id = id;
        this.parentSessionId = parentSessionId;
        this.agentId = agentId;
        this.activeModelId = activeModelId;
        this.title = title;
        this.createdDate = createdDate;
        this.records = records;
        this.systemPromptSnapshot = snapshot;
    }

    /** 会话开启 / 子代理派遣的唯一创建路径（应用层调用）。规则出处：模块-感知 §4、§7.1 规则 1。 */
    public static Session create(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId modelId,
                                 long now, SystemPromptSnapshot snapshot) {
        return new Session(id, parentSessionId, agentId, modelId, null, now,
                new ArrayList<Record>(), snapshot);
    }

    /** 会话续跑：从持久化形态重建，不重新派发事件。规则出处：模块-感知 §7.1 规则 11。 */
    public static Session rehydrate(SessionId id, SessionId parentSessionId, AgentId agentId, ModelId modelId,
                                    String title, long createdDate,
                                    List<Record> records, SystemPromptSnapshot snapshot) {
        return new Session(id, parentSessionId, agentId, modelId, title, createdDate,
                new ArrayList<Record>(records), snapshot);
    }

    /**
     * 续跑时把流水装回聚合：meta.json 只存会话头，记录在 RecordStore（§7.1 规则 11）。
     * 规则出处：模块-感知 §7.1 规则 5/11、M13。
     */
    public void hydrateRecords(List<Record> hydrated) {
        records.clear();
        records.addAll(hydrated);
    }

    /** 规则出处：模块-感知 §7.1 规则 5（只追加）。 */
    public Record appendRecord(RecordRole role, String content, ToolResultPayload payload, long now) {
        return appendRecord(role, content, null, payload, now);
    }

    /**
     * 追加一条带思维内容的记录：`reasoning` 只允许落在 TOOL_REQUEST 上。
     * 规则出处：模块-感知 §7.1 规则 15（reasoning 是回放用事实）。
     */
    public Record appendRecord(RecordRole role, String content, String reasoning, ToolResultPayload payload,
                               long now) {
        Record record = new Record(RecordId.of(java.util.UUID.randomUUID().toString()), role, content, reasoning,
                payload, 0, nextSequence(), now);
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
        int coveredThrough = latestSummary == null ? 0 : latestSummary.summaryCoversUpTo();
        List<Record> recent = recentAligned(policy.getRecordCount(), coveredThrough);

        int systemTokens = tokensOf(system, estimator);
        int summaryTokens = latestSummary == null ? 0 : tokensOf(
                java.util.Collections.singletonList(latestSummary), estimator);
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

    /**
     * 追加一条摘要，并记住它覆盖到哪条记录：窗口推导只取覆盖边界之后的记录，
     * 否则折叠不会让窗口变短（规则 9/11）。
     * 规则出处：模块-感知 §8.2 第 3 步、§7.1 规则 9 ③ / 规则 11。
     */
    public void markSummarized(String summary, int coveredThroughSequence, long now) {
        Record record = new Record(RecordId.of(java.util.UUID.randomUUID().toString()), RecordRole.SUMMARY,
                summary, null, null, coveredThroughSequence, nextSequence(), now);
        records.add(record);
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
        pendingEvents.add(new SessionCancelled());
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
     * 最近 n 条"对话"记录，但**按模型输出轮整轮对齐**、且只取摘要覆盖边界之后的记录。
     * 规则出处：模块-感知 §7.1 规则 9 ③、规则 10、规则 14。
     *
     * <p>对齐是请求合法性的前提：`assistant(tool_calls)` 与它的 `tool` 结果必须同进同出，
     * 否则模型端点会以 400 拒绝；这里不做"事后修补"，不变量违反直接抛。
     */
    private List<Record> recentAligned(int count, int coveredThroughSequence) {
        if (count <= 0) {
            return new ArrayList<>();
        }
        List<List<Record>> turns = turnsAfter(coveredThroughSequence);
        List<Record> out = new ArrayList<>();
        int index = turns.size() - 1;
        while (index >= 0 && (out.isEmpty() || out.size() < count)) {
            List<Record> turn = turns.get(index);
            out.addAll(0, turn);
            index--;
        }
        return out;
    }

    /**
     * 按"模型输出轮"分组：一轮 = `[ASSISTANT] + (TOOL_REQUEST + TOOL_RESULT)⁺`；
     * 无工具调用的回合自成一轮，USER / 其它角色也各自成轮。
     * 窗口推导与折叠都以这个单位裁剪，保证不切在轮中间（规则 14）。
     *
     * @param coveredThroughSequence 只取 `sequence` 大于它的记录（0 表示不排除）
     */
    public List<List<Record>> turnsAfter(int coveredThroughSequence) {
        List<Record> conversational = new ArrayList<>();
        for (Record record : records) {
            if (record.role() == RecordRole.SYSTEM || record.role() == RecordRole.SUMMARY) {
                continue;
            }
            if (record.sequence() <= coveredThroughSequence) {
                continue;
            }
            conversational.add(record);
        }
        List<List<Record>> turns = new ArrayList<>();
        List<Record> current = new ArrayList<>();
        for (Record record : conversational) {
            if (!current.isEmpty() && !continuesTurn(current.get(current.size() - 1).role(), record.role())) {
                turns.add(current);
                current = new ArrayList<>();
            }
            current.add(record);
        }
        if (!current.isEmpty()) {
            turns.add(current);
        }
        assertToolPairs(turns);
        return turns;
    }

    /** 同一个模型输出轮内的相邻关系；其余情况一律开新轮。 */
    private static boolean continuesTurn(RecordRole previous, RecordRole next) {
        if (previous == RecordRole.ASSISTANT) {
            return next == RecordRole.TOOL_REQUEST;
        }
        if (previous == RecordRole.TOOL_REQUEST) {
            return next == RecordRole.TOOL_RESULT;
        }
        if (previous == RecordRole.TOOL_RESULT) {
            // 一次模型输出里的多个工具调用会连续落成多对请求/结果
            return next == RecordRole.TOOL_REQUEST;
        }
        return false;
    }

    /** 每个 TOOL_REQUEST 必须紧跟它的 TOOL_RESULT；否则历史已不合法，不再"修补"。 */
    private static void assertToolPairs(List<List<Record>> turns) {
        for (List<Record> turn : turns) {
            boolean pending = false;
            for (Record record : turn) {
                if (record.role() == RecordRole.TOOL_REQUEST) {
                    if (pending) {
                        throw new DomainInvariantViolation("工具请求没有对应的结果：" + record.id().getValue());
                    }
                    pending = true;
                } else if (record.role() == RecordRole.TOOL_RESULT) {
                    if (!pending) {
                        throw new DomainInvariantViolation("工具结果没有对应的请求：" + record.id().getValue());
                    }
                    pending = false;
                } else if (pending) {
                    throw new DomainInvariantViolation("工具请求没有对应的结果：" + record.id().getValue());
                }
            }
            if (pending) {
                throw new DomainInvariantViolation("流水末尾有未闭合的工具请求");
            }
        }
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

    /** 计量口径：发出去多少算多少——带 reasoning 的工具请求把 reasoning 也算进去（规则 15）。 */
    private static int tokensOf(List<Record> records, TokenEstimator estimator) {
        int total = 0;
        for (Record r : records) {
            if (r.content() != null) {
                total += estimator.estimate(r.content());
            }
            if (r.reasoning() != null) {
                total += estimator.estimate(r.reasoning());
            }
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
