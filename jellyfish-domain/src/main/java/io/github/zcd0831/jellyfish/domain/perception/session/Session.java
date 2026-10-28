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
     * 追加一条带模型私有状态的记录：`reasoning` + `producerModelId` 一起写，回放时按产者过滤。
     * 规则出处：模块-感知 §7.1 规则 15。
     */
    public Record appendRecord(RecordRole role, String content, String reasoning, ToolResultPayload payload,
                               long now) {
        return appendRecord(role, content, reasoning, null, null, payload, now);
    }

    /**
     * 追加一条属于某次模型响应的记录：同一响应的全部记录共享 `turnId`。
     * 规则出处：模块-感知 §7.1 规则 14/17。
     */
    public Record appendRecord(RecordRole role, String content, String reasoning, String turnId,
                               String producerModelId, ToolResultPayload payload, long now) {
        Record record = new Record(RecordId.of(java.util.UUID.randomUUID().toString()), role, content, reasoning,
                turnId, producerModelId, payload, 0, nextSequence(), now);
        records.add(record);
        return record;
    }

    private int nextSequence() {
        return records.isEmpty() ? 1 : records.get(records.size() - 1).sequence() + 1;
    }

    /**
     * 系统提示词不在这里：它来自冻结快照，由调用方单独传给模型（规则 13）。
     */
    /**
     * 两区窗口：最新 SUMMARY + 覆盖边界之后的**全部**记录（含 SYSTEM 运行期注入）。
     * 不读任何配置、不设条数上限——前沿由覆盖边界固定，后沿随对话持续下移。
     * 规则出处：04-perception §4.8.1 规则 9/10、§4.9.5。
     */
    public ContextWindow buildContextForLlm(TokenEstimator estimator, int inputCeiling) {
        Record latestSummary = latest(RecordRole.SUMMARY);
        int coveredThrough = latestSummary == null ? 0 : latestSummary.summaryCoversUpTo();
        List<Record> windowRecords = afterBoundary(coveredThrough);
        assertTurnShape(turnsAfter(coveredThrough));

        int systemTokens = tokensOf(systemRecordsIn(windowRecords), estimator);
        int summaryTokens = latestSummary == null ? 0
                : tokensOf(java.util.Collections.singletonList(latestSummary), estimator);
        int usedTokens = summaryTokens + tokensOf(windowRecords, estimator);

        List<Record> records = new ArrayList<Record>();
        if (latestSummary != null) {
            records.add(latestSummary);
        }
        records.addAll(windowRecords);
        return new ContextWindow(records, new ContextUsage(usedTokens, inputCeiling, systemTokens,
                summaryTokens, records.size()));
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
    /** 覆盖边界之后的记录：含 SYSTEM 注入，SUMMARY 由窗口首条单独承载。 */
    private List<Record> afterBoundary(int coveredThroughSequence) {
        List<Record> out = new ArrayList<Record>();
        for (Record record : records) {
            if (record.role() == RecordRole.SUMMARY || record.sequence() <= coveredThroughSequence) {
                continue;
            }
            out.add(record);
        }
        return out;
    }

    private static List<Record> systemRecordsIn(List<Record> records) {
        List<Record> out = new ArrayList<Record>();
        for (Record record : records) {
            if (record.role() == RecordRole.SYSTEM) {
                out.add(record);
            }
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
        String currentTurnId = null;
        for (Record record : conversational) {
            // turnId 同为 null 的两条记录（如 USER）不属同一轮：null 表示"各自成轮"
            boolean sameTurn = record.turnId() != null && record.turnId().equals(currentTurnId);
            if (!current.isEmpty() && !sameTurn) {
                turns.add(current);
                current = new ArrayList<>();
            }
            current.add(record);
            currentTurnId = record.turnId();
        }
        if (!current.isEmpty()) {
            turns.add(current);
        }
        assertTurnShape(turns);
        return turns;
    }

    /**
     * 一次模型响应只能有一条 assistant 消息：`ASSISTANT` 至多一条，且工具请求/结果必须成对。
     * 规则出处：模块-感知 §7.1 规则 14/17。
     */
    private static void assertTurnShape(List<List<Record>> turns) {
        for (List<Record> turn : turns) {
            int assistants = 0;
            boolean pending = false;
            for (Record record : turn) {
                if (record.role() == RecordRole.ASSISTANT) {
                    assistants++;
                    if (pending) {
                        throw new DomainInvariantViolation("工具请求没有对应的结果：" + record.id().getValue());
                    }
                } else if (record.role() == RecordRole.TOOL_REQUEST) {
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
            if (assistants > 1) {
                throw new DomainInvariantViolation("同一轮模型响应出现了多条 assistant 记录");
            }
        }
    }

    /** 是否存在未拿到结果的工具请求（切换模型前的硬闸）。规则出处：模块-感知 §7.1 规则 2。 */
    public boolean hasOpenToolRound() {
        int open = 0;
        for (Record record : records) {
            if (record.role() == RecordRole.TOOL_REQUEST) {
                open++;
            } else if (record.role() == RecordRole.TOOL_RESULT && open > 0) {
                open--;
            }
        }
        return open > 0;
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
