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
import java.util.UUID;

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

    /**
     * 内循环的唯一出口：模型不再调工具。`submit_step` 由内循环识别，不当作普通工具执行。
     *
     * <p>消息形态：**一次模型响应 = 一条 assistant 消息**（content + 全部 tool_calls），
     * 其工具结果按调用顺序紧随其后；同一次响应落盘的记录共享 `turnId`。
     * 规则出处：模块-规划 §7.1 规则 11、模块-感知 §7.1 规则 14/15/17。
     */
    public IterationResult runIteration(Session session, PlanStep step, ChunkSink sink) {
        RuntimePolicy policy = policies.load();
        ContextWindow window = contextWindow.currentWindow(session);
        List<ChatMessage> messages = toMessages(window, session.activeModelId());
        List<SkillId> usedSkills = new ArrayList<SkillId>();
        // 已落 TOOL_REQUEST、尚未落 TOOL_RESULT 的调用：环境故障/取消时必须补成"中止结果"，
        // 否则流水留下未闭合的工具请求，下一次重建请求必然非法（模块-规划 §7.1 规则 9）。
        List<ToolCall> pending = new ArrayList<ToolCall>();
        String currentTurnId = null;
        String currentProducer = null;
        try {
            for (int round = 1; round <= policy.maxReActRounds(); round++) {
                ModelInvocationResult result = invoke(session, step, window, messages, sink);
                syncModel(session, result.servedModelId());
                if (result.usage() != null && budgetService.recordUsage(session, result.usage().total())) {
                    askContinue();
                }
                String turnId = UUID.randomUUID().toString();
                String producer = producerOf(session, result);
                currentTurnId = turnId;
                currentProducer = producer;
                String text = emptyToNull(result.text());
                // 模型私有状态记在本轮首条记录上：有文本记在 ASSISTANT，否则记在第一条 TOOL_REQUEST
                if (text != null) {
                    Record record = session.appendRecord(RecordRole.ASSISTANT, text, result.reasoning(),
                            turnId, producer, null, System.currentTimeMillis());
                    commitAppend(session, record);
                }
                if (result.toolCalls().isEmpty()) {
                    return IterationResult.converged(round, usedSkills);
                }
                // 一个响应一条 assistant 消息：content 与全部 tool_calls 同处一条
                messages.add(ChatMessage.assistant(text, result.toolCalls(), result.reasoning()));
                String submittedSummary = null;
                boolean first = true;
                for (ToolCall call : result.toolCalls()) {
                    String reasoning = (text == null && first) ? result.reasoning() : null;
                    first = false;
                    if (SUBMIT_STEP.equals(call.name())) {
                        submittedSummary = summaryOf(call);
                        messages.add(submit(session, call, submittedSummary, reasoning, turnId, producer, pending));
                        continue;
                    }
                    if (LOAD_SKILL.equals(call.name())) {
                        recordSkillUse(call, usedSkills);
                    }
                    messages.add(executeTool(session, call, reasoning, turnId, producer, pending));
                }
                if (submittedSummary != null) {
                    return IterationResult.submitted(submittedSummary, round, usedSkills);
                }
            }
            return IterationResult.notConverged(policy.maxReActRounds(), usedSkills);
        } catch (RuntimeException failure) {
            closePendingToolCalls(session, pending, currentTurnId, currentProducer);
            throw failure;
        }
    }

    private static String emptyToNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    /** 产者模型 = 实际服务的模型（降级后与目标不同）；拿不到时退回会话当前模型。 */
    private static String producerOf(Session session, ModelInvocationResult result) {
        return result.servedModelId() == null ? session.activeModelId().getValue()
                : result.servedModelId().getValue();
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
    private ChatMessage submit(Session session, ToolCall call, String summary, String reasoning, String turnId,
                               String producer, List<ToolCall> pending) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), reasoning, turnId,
                producer, null, System.currentTimeMillis());
        commitAppend(session, request);
        pending.add(call);
        Record result = session.appendRecord(RecordRole.TOOL_RESULT, "已登记，等待验收", null, turnId,
                producer, null, System.currentTimeMillis());
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

    private ChatMessage executeTool(Session session, ToolCall call, String reasoning, String turnId,
                                    String producer, List<ToolCall> pending) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), reasoning, turnId,
                producer, null, System.currentTimeMillis());
        commitAppend(session, request);
        pending.add(call);

        Map<String, Object> args = Json.parseObject(call.argumentsJson());
        ToolExecOutcome outcome = toolExecutor.execute(session, call.name(), args, request.id());

        Record result = session.appendRecord(RecordRole.TOOL_RESULT, outcome.content(), null, turnId,
                producer, new ToolResultPayload(outcome.isTruncated(), outcome.resourceUri()),
                System.currentTimeMillis());
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
    private void closePendingToolCalls(Session session, List<ToolCall> pending, String turnId, String producer) {
        for (ToolCall call : pending) {
            try {
                Record aborted = session.appendRecord(RecordRole.TOOL_RESULT, ABORTED_TOOL_RESULT, null,
                        turnId, producer, null, System.currentTimeMillis());
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
     * 窗口 → 消息：摘要按 user 回灌；同一 `turnId` 的记录还原成**一条** assistant 消息
     * （content + 全部 tool_calls）+ 按调用顺序排列的 tool 消息；reasoning 只回放给产者模型。
     * 规则出处：模块-感知 §7.1 规则 14/15/16/17、模块-行动 §8.2 第 6/7 条。
     */
    private static List<ChatMessage> toMessages(ContextWindow window, ModelId targetModelId) {
        List<Record> records = window.getRecords();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        int i = 0;
        while (i < records.size()) {
            Record record = records.get(i);
            if (record.role() == RecordRole.SYSTEM) {
                i++;   // 已并入 systemPrompt（模块-感知 §7.1 规则 13）
                continue;
            }
            if (record.role() == RecordRole.SUMMARY) {
                messages.add(ChatMessage.of(RecordRole.SUMMARY, SUMMARY_PREFIX + record.content()));
                i++;
                continue;
            }
            if (record.turnId() == null) {
                messages.add(ChatMessage.of(record.role(), record.content()));
                i++;
                continue;
            }
            int end = i;
            while (end < records.size() && record.turnId().equals(records.get(end).turnId())) {
                end++;
            }
            appendTurn(records.subList(i, end), targetModelId, messages);
            i = end;
        }
        return messages;
    }

    /** 一轮模型输出：一条 assistant 消息 + 若干 tool 消息。 */
    private static void appendTurn(List<Record> turn, ModelId targetModelId, List<ChatMessage> messages) {
        String content = null;
        String reasoning = null;
        String producer = null;
        List<ToolCall> calls = new ArrayList<ToolCall>();
        List<String> results = new ArrayList<String>();
        for (Record record : turn) {
            if (record.role() == RecordRole.ASSISTANT) {
                content = record.content();
            } else if (record.role() == RecordRole.TOOL_REQUEST) {
                calls.addAll(parseCalls(record.content()));
            } else if (record.role() == RecordRole.TOOL_RESULT) {
                results.add(record.content());
            }
            if (reasoning == null) {
                reasoning = record.reasoning();
            }
            if (producer == null) {
                producer = record.producerModelId();
            }
        }
        String replayReasoning = producer != null && producer.equals(targetModelId.getValue()) ? reasoning : null;
        if (calls.isEmpty()) {
            if (content != null) {
                messages.add(ChatMessage.assistant(content, Collections.<ToolCall>emptyList(), replayReasoning));
            }
            return;
        }
        if (calls.size() != results.size()) {
            throw new DomainInvariantViolation("工具请求与结果数量不一致：轮 " + turn.get(0).turnId());
        }
        messages.add(ChatMessage.assistant(content, calls, replayReasoning));
        for (int k = 0; k < calls.size(); k++) {
            messages.add(ChatMessage.toolResult(calls.get(k).callId(), results.get(k)));
        }
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
}
