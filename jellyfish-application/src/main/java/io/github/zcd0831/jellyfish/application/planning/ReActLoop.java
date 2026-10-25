package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.application.shared.JsonText;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内循环：组装上下文 → 调模型（可流式）→ 执行工具 → 落流水；唯一出口是"模型不再调工具"。
 * 规则出处：模块-规划 §8.2、模块-感知 §8.5。
 */
public final class ReActLoop {

    private static final String SUBMIT_STEP = "submit_step";
    private static final String LOAD_SKILL = "load_skill";
    /** 摘要作为一条 user 消息回灌；前缀是模型可见文案，不是用户文案。 */
    private static final String SUMMARY_PREFIX = "【更早对话的摘要】\n";
    /** 环境故障/取消时，给未闭合的工具调用补的中止事实。 */
    private static final String ABORTED_TOOL_RESULT = "（本轮中止：模型不可用或用户取消，工具结果未回灌）";

    private final ModelInvocationPort model;
    private final ToolExecutor toolExecutor;
    private final BudgetService budgetService;
    private final ContextWindowService contextWindow;
    private final PolicyRepository policies;
    private final CommitScope commitScope;
    private final HumanInteractionPort human;

    public ReActLoop(ModelInvocationPort model, ToolExecutor toolExecutor, BudgetService budgetService,
                     ContextWindowService contextWindow, PolicyRepository policies, CommitScope commitScope,
                     HumanInteractionPort human) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.budgetService = budgetService;
        this.contextWindow = contextWindow;
        this.policies = policies;
        this.commitScope = commitScope;
        this.human = human;
    }

    /** 内循环的唯一出口：模型不再调工具。`submit_step` 由内循环识别，不当作普通工具执行。 */
    public IterationResult runIteration(Session session, PlanStep step, ChunkSink sink) {
        RuntimePolicy policy = policies.load();
        ContextWindow window = contextWindow.currentWindow(session);
        List<ChatMessage> messages = toMessages(window);
        List<SkillId> usedSkills = new ArrayList<SkillId>();
        // 已落 TOOL_REQUEST、尚未落 TOOL_RESULT 的调用：环境故障/取消时必须补成"中止结果"，
        // 否则流水留下未闭合的工具请求，下一次重建请求必然非法（模块-规划 §7.1 规则 9）。
        List<ToolCall> pending = new ArrayList<ToolCall>();
        try {
            for (int round = 1; round <= policy.maxReActRounds(); round++) {
                ModelInvocationResult result = invoke(session, step, window, messages, sink);
                syncModel(session, result.servedModelId());
                if (result.usage() != null && budgetService.recordUsage(session, result.usage().total())) {
                    askContinue();
                }
                if (result.text() != null && !result.text().isEmpty()) {
                    Record record = session.appendRecord(RecordRole.ASSISTANT, result.text(), null,
                            System.currentTimeMillis());
                    commitAppend(session, record);
                    messages.add(ChatMessage.of(RecordRole.ASSISTANT, result.text()));
                }
                if (result.toolCalls().isEmpty()) {
                    return IterationResult.converged(round, usedSkills);
                }
                String submittedSummary = null;
                for (ToolCall call : result.toolCalls()) {
                    messages.add(ChatMessage.assistant(null, Collections.singletonList(call),
                            result.reasoning()));
                    if (SUBMIT_STEP.equals(call.name())) {
                        submittedSummary = summaryOf(call);
                        messages.add(submit(session, call, submittedSummary, result.reasoning(), pending));
                        continue;
                    }
                    if (LOAD_SKILL.equals(call.name())) {
                        recordSkillUse(call, usedSkills);
                    }
                    messages.add(executeTool(session, call, result.reasoning(), pending));
                }
                if (submittedSummary != null) {
                    return IterationResult.submitted(submittedSummary, round, usedSkills);
                }
            }
            return IterationResult.notConverged(policy.maxReActRounds(), usedSkills);
        } catch (RuntimeException failure) {
            closePendingToolCalls(session, pending);
            throw failure;
        }
    }

    private ModelInvocationResult invoke(Session session, PlanStep step, ContextWindow window,
                                         List<ChatMessage> messages, ChunkSink sink) {
        ModelInvocationRequest request = new ModelInvocationRequest(session.activeModelId(),
                systemPrompt(session, step), messages, tools(session),
                window.getUsage().getUsedTokens(), null);
        return sink == null ? model.invoke(request) : model.invokeStreaming(request, sink);
    }

    /**
     * 系统提示词 = 冻结快照 + 运行期注入（SYSTEM 记录）；工具清单同理取快照。
     * 规则出处：模块-感知 §7.1 规则 13、§8.5；技术决策 M02/M12。
     */
    private static String systemPrompt(Session session, PlanStep step) {
        StringBuilder prompt = new StringBuilder();
        if (session.systemPromptSnapshot() != null && session.systemPromptSnapshot().getRenderedPrompt() != null) {
            prompt.append(session.systemPromptSnapshot().getRenderedPrompt());
        }
        for (Record record : session.records()) {
            if (record.role() == RecordRole.SYSTEM) {
                prompt.append('\n').append(record.content());
            }
        }
        if (step != null) {
            // 当前步骤是运行期信息，不属于冻结快照：每次请求临时附在 system 里
            prompt.append("\n\n当前步骤（第 ").append(step.sequence()).append(" 步）：")
                    .append(step.description());
        }
        return prompt.toString();
    }

    private static List<ToolSchemaSnapshot> tools(Session session) {
        return session.systemPromptSnapshot() == null
                ? Collections.<ToolSchemaSnapshot>emptyList()
                : session.systemPromptSnapshot().getToolList();
    }

    /** submit_step 只登记，不执行：真正的判定在外循环的验证链（§8.2）。 */
    private ChatMessage submit(Session session, ToolCall call, String summary, String reasoning,
                               List<ToolCall> pending) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), reasoning, null,
                System.currentTimeMillis());
        commitAppend(session, request);
        pending.add(call);
        Record result = session.appendRecord(RecordRole.TOOL_RESULT, "已登记，等待验收", null,
                System.currentTimeMillis());
        pending.remove(call);
        commitAppend(session, result);
        return ChatMessage.toolResult(call.callId(), "已登记，等待验收");
    }

    private static String summaryOf(ToolCall call) {
        Object summary = Json.parseObject(call.argumentsJson()).get("summary");
        return summary == null ? "" : String.valueOf(summary);
    }

    /** 记下"本次真的加载了哪个技能"：改进判定的唯一证据（§8.6）。 */
    private static void recordSkillUse(ToolCall call, List<SkillId> usedSkills) {
        Object skillName = Json.parseObject(call.argumentsJson()).get("skillName");
        if (skillName != null) {
            usedSkills.add(SkillId.of(String.valueOf(skillName)));
        }
    }

    private ChatMessage executeTool(Session session, ToolCall call, String reasoning, List<ToolCall> pending) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), reasoning, null,
                System.currentTimeMillis());
        commitAppend(session, request);
        pending.add(call);

        Map<String, Object> args = Json.parseObject(call.argumentsJson());
        ToolExecOutcome outcome = toolExecutor.execute(session, call.name(), args, request.id());

        Record result = session.appendRecord(RecordRole.TOOL_RESULT, outcome.content(),
                new ToolResultPayload(outcome.isTruncated(), outcome.resourceUri()), System.currentTimeMillis());
        pending.remove(call);
        commitAppend(session, result);
        if (LOAD_SKILL.equals(call.name())) {
            // SOP 注入为 SYSTEM 记录：此后不参与滑窗（§7.3 规则 ③）
            Record injected = session.appendRecord(RecordRole.SYSTEM, outcome.content(), null,
                    System.currentTimeMillis());
            commitAppend(session, injected);
        }
        return ChatMessage.toolResult(call.callId(), outcome.content());
    }

    /** 异常路径的收尾：把没拿到结果的工具调用补成一条中止事实（只追加，不撤销已发生的副作用）。 */
    private void closePendingToolCalls(Session session, List<ToolCall> pending) {
        for (ToolCall call : pending) {
            try {
                Record aborted = session.appendRecord(RecordRole.TOOL_RESULT, ABORTED_TOOL_RESULT, null,
                        System.currentTimeMillis());
                commitAppend(session, aborted);
            } catch (RuntimeException cannotRecord) {
                // 收尾失败不掩盖原始异常：流水残留在下次启动对账里处理（模块-应用层 §9）
                return;
            }
        }
        pending.clear();
    }

    /** 工具请求以单行 JSON 落流水，供续跑时重建调用关系。 */
    private static String describe(ToolCall call) {
        return "{\"callId\":\"" + JsonText.escape(call.callId()) + "\",\"name\":\""
                + JsonText.escape(call.name()) + "\",\"arguments\":\""
                + JsonText.escape(call.argumentsJson()) + "\"}";
    }

    private void syncModel(Session session, ModelId served) {
        if (served == null || served.equals(session.activeModelId())) {
            return;
        }
        session.switchModel(served);
        commitScope.stageReplace(session);
        commitScope.commit();
    }

    private void commitAppend(Session session, Record record) {
        commitScope.stageAppend(session.id(), record);
        commitScope.commit();
    }

    private void askContinue() {
        Answer answer = human.ask(new Question(Question.Source.BUDGET, "本族 token 已越过预警线，继续吗？", null));
        if (answer.kind() == Answer.Kind.CANCELLED) {
            throw new java.util.concurrent.CancellationException("用户在预算预警处取消");
        }
    }

    /**
     * 窗口 → 消息：摘要按 user 回灌；工具请求与它紧跟的结果成对还原（tool_call_id 不能丢）。
     * 规则出处：模块-感知 §7.1 规则 14/16、模块-行动 §8.2 第 6 条。
     */
    private static List<ChatMessage> toMessages(ContextWindow window) {
        List<Record> records = window.getRecords();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            switch (record.role()) {
                case SYSTEM:
                    continue;   // 已并入 systemPrompt（模块-感知 §7.1 规则 13）
                case SUMMARY:
                    messages.add(ChatMessage.of(RecordRole.SUMMARY, SUMMARY_PREFIX + record.content()));
                    break;
                case ASSISTANT:
                    messages.add(ChatMessage.of(RecordRole.ASSISTANT, record.content()));
                    break;
                case TOOL_REQUEST:
                    messages.add(ChatMessage.assistant(null, parseCalls(record.content()), record.reasoning()));
                    if (i + 1 >= records.size() || records.get(i + 1).role() != RecordRole.TOOL_RESULT) {
                        throw new DomainInvariantViolation("窗口里工具请求没有紧跟结果：" + record.id().getValue());
                    }
                    messages.add(ChatMessage.toolResult(callIdOf(record.content()),
                            records.get(++i).content()));
                    break;
                case TOOL_RESULT:
                    throw new DomainInvariantViolation("窗口里工具结果没有对应的请求：" + record.id().getValue());
                default:
                    messages.add(ChatMessage.of(record.role(), record.content()));
            }
        }
        return messages;
    }

    private static List<ToolCall> parseCalls(String content) {
        Map<String, Object> fields = Json.parseObject(content);
        Object name = fields.get("name");
        if (name == null) {
            return Collections.emptyList();
        }
        Object callId = fields.get("callId");
        Object arguments = fields.get("arguments");
        return Collections.singletonList(new ToolCall(
                callId == null ? "" : String.valueOf(callId), String.valueOf(name),
                arguments == null ? "{}" : String.valueOf(arguments)));
    }

    private static String callIdOf(String content) {
        Map<String, Object> fields = Json.parseObject(content);
        Object callId = fields.get("callId");
        return callId == null ? "" : String.valueOf(callId);
    }
}
