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
import io.github.zcd0831.jellyfish.application.perception.ConversationMessages;
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
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内循环：组装上下文 → 调模型（可流式）→ 执行工具 → 落流水；唯一出口是"模型不再调工具"。
 * 规则出处：06-planning §6.9.2、04-perception §4.9.2。
 */
public final class ReActLoop {

    private static final String SUBMIT_STEP = "submit_step";
    private static final String LOAD_SKILL = "load_skill";
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
     * <p>消息形态：**一次模型响应 = 一条 assistant 消息**（content + 全部 tool_calls），
     * 其工具结果按调用顺序紧随其后；同一次响应落盘的记录共享 `turnId`。
     * 规则出处：06-planning §6.8.1 规则 11、04-perception §4.8.1 规则 9/14/17。
     */
    public IterationResult runIteration(Session session, PlanStep step, ChunkSink sink) {
        RuntimePolicy policy = policies.load();
        List<SkillId> usedSkills = new ArrayList<SkillId>();
        // 已落 TOOL_REQUEST、尚未落 TOOL_RESULT 的调用：环境故障/取消时必须补成"中止结果"，
        // 否则流水留下未闭合的工具请求，下一次重建请求必然非法（06-planning §6.8.1 规则 9）。
        List<ToolCall> pending = new ArrayList<ToolCall>();
        String currentTurnId = null;
        String currentProducer = null;
        try {
            for (int round = 1; round <= policy.maxReActRounds(); round++) {
                // 折叠挂在每次请求前、消息每轮重建：否则一轮内多次工具调用会把请求撑爆（04-perception 规则 18）
                contextWindow.ensureWithinLimit(session);
                ContextWindow window = contextWindow.currentWindow(session);
                List<ChatMessage> messages = ConversationMessages.of(window, session.activeModelId());
                appendStepNote(step, messages);
                ModelInvocationResult result = invoke(session, window, messages, sink);
                syncModel(session, result.servedModelId());
                if (result.usage() != null && contextWindow.recordTurnUsage(session, result.usage())) {
                    askContinue();
                }
                String turnId = UUID.randomUUID().toString();
                String producer = producerOf(session, result);
                currentTurnId = turnId;
                currentProducer = producer;
                String text = emptyToNull(result.text());
                if (text != null) {
                    Record record = session.appendRecord(RecordRole.ASSISTANT, text, result.reasoning(),
                            turnId, producer, null, System.currentTimeMillis());
                    commitAppend(session, record);
                }
                if (result.toolCalls().isEmpty()) {
                    return IterationResult.converged(round, usedSkills);
                }
                String submittedSummary = null;
                boolean first = true;
                for (ToolCall call : result.toolCalls()) {
                    String reasoning = (text == null && first) ? result.reasoning() : null;
                    first = false;
                    if (SUBMIT_STEP.equals(call.name())) {
                        submittedSummary = summaryOf(call);
                        submit(session, call, reasoning, turnId, producer, pending);
                        continue;
                    }
                    if (LOAD_SKILL.equals(call.name())) {
                        recordSkillUse(call, usedSkills);
                    }
                    executeTool(session, call, reasoning, turnId, producer, pending);
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

    private ModelInvocationResult invoke(Session session, ContextWindow window,
                                         List<ChatMessage> messages, ChunkSink sink) {
        ModelInvocationRequest request = new ModelInvocationRequest(session.id(), session.activeModelId(),
                systemPrompt(session), messages, tools(session),
                window.getUsage().getUsedTokens(), null);
        return sink == null ? model.invoke(request) : model.invokeStreaming(request, sink);
    }

    /** system 字段只有冻结快照：运行期注入走流水，否则前缀每轮都变、缓存全废（规则 13）。 */
    private static String systemPrompt(Session session) {
        SystemPromptSnapshot snapshot = session.systemPromptSnapshot();
        return snapshot == null || snapshot.getRenderedPrompt() == null ? "" : snapshot.getRenderedPrompt();
    }

    /** 当前步骤由 Plan 推导，不落 Record：追加在末尾不动前缀，折叠后也不会丢（规则 13）。 */
    private static void appendStepNote(PlanStep step, List<ChatMessage> messages) {
        if (step != null) {
            messages.add(ChatMessage.of(RecordRole.SYSTEM,
                    "当前步骤（第 " + step.sequence() + " 步）：" + step.description()));
        }
    }

    private static List<ToolSchemaSnapshot> tools(Session session) {
        return session.systemPromptSnapshot() == null
                ? Collections.<ToolSchemaSnapshot>emptyList()
                : session.systemPromptSnapshot().getToolList();
    }

    /** submit_step 只登记，不执行：真正的判定在外循环的验证链（06-planning §6.8.2）。 */
    private void submit(Session session, ToolCall call, String reasoning, String turnId,
                        String producer, List<ToolCall> pending) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), reasoning, turnId,
                producer, null, System.currentTimeMillis());
        commitAppend(session, request);
        pending.add(call);
        Record result = session.appendRecord(RecordRole.TOOL_RESULT, "已登记，等待验收", null, turnId,
                producer, null, System.currentTimeMillis());
        pending.remove(call);
        commitAppend(session, result);
    }

    private static String summaryOf(ToolCall call) {
        Object summary = Json.parseObject(call.argumentsJson()).get("summary");
        return summary == null ? "" : String.valueOf(summary);
    }

    /** 记下"本次真的加载了哪个技能"：改进判定的唯一证据（07-memory §7.8.3）。 */
    private static void recordSkillUse(ToolCall call, List<SkillId> usedSkills) {
        Object skillName = Json.parseObject(call.argumentsJson()).get("skillName");
        if (skillName != null) {
            usedSkills.add(SkillId.of(String.valueOf(skillName)));
        }
    }

    private void executeTool(Session session, ToolCall call, String reasoning, String turnId,
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
            // SOP 注入为 SYSTEM 记录：与普通记录同受覆盖边界约束（规则 10）
            Record injected = session.appendRecord(RecordRole.SYSTEM, outcome.content(), null,
                    System.currentTimeMillis());
            commitAppend(session, injected);
        }
    }

    /** 异常路径的收尾：把没拿到结果的工具调用补成一条中止事实（只追加，不撤销已发生的副作用）。 */
    private void closePendingToolCalls(Session session, List<ToolCall> pending, String turnId, String producer) {
        for (ToolCall call : pending) {
            try {
                Record aborted = session.appendRecord(RecordRole.TOOL_RESULT, ABORTED_TOOL_RESULT, null,
                        turnId, producer, null, System.currentTimeMillis());
                commitAppend(session, aborted);
            } catch (RuntimeException cannotRecord) {
                // 收尾失败不掩盖原始异常：流水残留在下次启动对账里处理（08-application §8.10）
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

}
