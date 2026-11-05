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

/** 会话聚合根。规则出处：04-perception §4.4.1、§4.8.1、§4.9.5。 */
public class Session {

    /** 身份与冻结快照都收在 header 里：开新会话与重建走同一条装配路径。 */
    private final SessionHeader header;
    private final List<Record> records;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private ModelId activeModelId;
    private String title;

    private Session(SessionHeader header, List<Record> records) {
        this.header = header;
        this.activeModelId = header.modelId();
        this.title = header.title();
        this.records = records;
    }

    /** 会话开启 / 子代理派遣的唯一创建路径（应用层调用）。规则出处：04-perception §4.5、§4.8.1 规则 1。 */
    public static Session create(SessionHeader header) {
        Session session = new Session(header, new ArrayList<Record>());
        // 创建即事实：会话开启与子代理派遣同一路径（04-perception §4.7）
        session.pendingEvents.add(new SessionStarted());
        return session;
    }

    /** 会话续跑：从持久化形态重建，**不重发 SessionStarted**（续跑不是状态迁移）。
     * 规则出处：04-perception §4.8.1 规则 7、§4.7。 */
    public static Session rehydrate(SessionHeader header, List<Record> restored) {
        return new Session(header, new ArrayList<Record>(restored));
    }

    /**
     * 续跑时把流水装回聚合：meta.json 只存会话头，记录在 RecordStore（04-perception §4.8.1 规则 11）。
     * 规则出处：04-perception §4.8.1 规则 5/11。
     */
    public void hydrateRecords(List<Record> hydrated) {
        records.clear();
        records.addAll(hydrated);
    }

    /**
     * 追加一条记录。编号（`sequence`）与时间由会话决定，调用方只能通过 {@link RecordDraft}
     * 描述"这是什么"——位置参数一多就会把 reasoning 写进 turnId（规则 5、14、15）。
     */
    public Record appendRecord(RecordDraft draft, long now) {
        Record record = Record.restore(RecordId.of(java.util.UUID.randomUUID().toString()), draft,
                nextSequence(), now);
        records.add(record);
        return record;
    }

    /** 只带角色与正文的常见形态。规则出处：04-perception §4.8.1 规则 5（只追加）。 */
    public Record appendRecord(RecordRole role, String content, long now) {
        return appendRecord(RecordDraft.builder(role, content).build(), now);
    }

    /** 带模型私有状态（reasoning）：只回放给产者（规则 15）。 */
    public Record appendRecord(RecordRole role, String content, String reasoning, long now) {
        return appendRecord(RecordDraft.builder(role, content).reasoning(reasoning).build(), now);
    }


    private int nextSequence() {
        return records.isEmpty() ? 1 : records.get(records.size() - 1).sequence() + 1;
    }

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
     * 规则出处：04-perception §4.9.2 第 3 步、§4.8.1 规则 9 ③ / 规则 11。
     */
    public void markSummarized(String summary, int coveredThroughSequence, long now) {
        int floor = latest(RecordRole.SUMMARY) == null ? 0 : latest(RecordRole.SUMMARY).summaryCoversUpTo();
        int ceiling = records.isEmpty() ? 0 : records.get(records.size() - 1).sequence();
        if (coveredThroughSequence < floor) {
            throw new DomainInvariantViolation("摘要覆盖边界不能倒退：" + coveredThroughSequence
                    + " < " + floor);
        }
        if (coveredThroughSequence > ceiling) {
            throw new DomainInvariantViolation("摘要覆盖边界越界：" + coveredThroughSequence
                    + " > " + ceiling);
        }
        assertTurnBoundary(coveredThroughSequence);
        Record record = appendRecord(RecordDraft.builder(RecordRole.SUMMARY, summary)
                .summaryCoversUpTo(coveredThroughSequence).build(), now);
        pendingEvents.add(new SummarizationCompleted());
    }

    /** 只有降级会切模型；Agent 不可切换。规则出处：04-perception §4.8.1 规则 2、§4.7。 */
    public void switchModel(ModelId modelId) {
        if (modelId.equals(activeModelId)) {
            return;
        }
        this.activeModelId = modelId;
        pendingEvents.add(new ModelSwitched());
    }

    /** 规则出处：04-perception §4.8.1 规则 6（父取消级联子由应用层执行）。 */
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
     * 规则出处：04-perception §4.8.1 规则 14/17。
     */
    private static void assertTurnShape(List<List<Record>> turns) {
        for (List<Record> turn : turns) {
            assertTurnHead(turn);
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

    /**
     * 轮首判定：用户输入自成一"轮"，不能与其他记录共享 turnId。
     * 注意这里<strong>不</strong>要求轮首是 ASSISTANT：模型只调工具、不写字时
     * 这一轮在流水里就是 [TOOL_REQUEST…]，没有 assistant 记录可打头（04 §4.8.1 规则 14）。
     * 折叠边界切在一轮中间由 {@link #assertTurnBoundary} 在写入时拦住。
     * 规则出处：04-perception §4.8.1 规则 14。
     */
    private static void assertTurnHead(List<Record> turn) {
        Record head = turn.get(0);
        if (head.role() == RecordRole.USER && turn.size() > 1) {
            throw new DomainInvariantViolation("用户输入自成一轮，不能与其他记录同轮："
                    + head.id().getValue());
        }
    }

    /**
     * 折叠边界必须落在轮边界上：边界前后不能是同一轮的两条记录。
     * 切在轮中间会让窗口以 [TOOL_REQUEST, TOOL_RESULT] 打头，那条 assistant 原文就凭空消失
     * （发给模型时 content 变 null），而形状检查发现不了。
     * 规则出处：04-perception §4.8.1（"折叠边界必须落在轮边界，否则窗口里会留下半截工具轮"）、规则 14。
     */
    private void assertTurnBoundary(int coveredThroughSequence) {
        if (coveredThroughSequence <= 0) {
            return;   // 边界在流水起点之前：整窗保留，不存在切轮
        }
        Record before = null;
        Record after = null;
        for (Record record : records) {
            if (record.role() == RecordRole.SYSTEM || record.role() == RecordRole.SUMMARY) {
                continue;   // 运行期注入与摘要不是"轮"的成员
            }
            if (record.sequence() <= coveredThroughSequence) {
                before = record;
            } else if (after == null) {
                after = record;
            }
        }
        if (before != null && after != null && before.turnId() != null
                && before.turnId().equals(after.turnId())) {
            throw new DomainInvariantViolation("摘要覆盖边界切在轮中间（turnId="
                    + before.turnId() + "，记录 " + before.sequence() + "/" + after.sequence() + "）");
        }
    }

    /** 是否存在未拿到结果的工具请求（切换模型前的硬闸）。规则出处：04-perception §4.8.1 规则 2。 */
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
        return header.id();
    }

    public SessionId parentSessionId() {
        return header.parentSessionId();
    }

    public AgentId agentId() {
        return header.agentId();
    }

    public ModelId activeModelId() {
        return activeModelId;
    }

    public String title() {
        return title;
    }

    public long createdDate() {
        return header.createdDate();
    }

    /** 只读视图：调用方不得修改流水（§7.1 规则 5）。 */
    public List<Record> records() {
        return java.util.Collections.unmodifiableList(records);
    }

    public SystemPromptSnapshot systemPromptSnapshot() {
        return header.systemPromptSnapshot();
    }
}
