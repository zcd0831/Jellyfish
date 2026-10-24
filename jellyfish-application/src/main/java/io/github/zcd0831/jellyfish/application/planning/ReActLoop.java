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
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStep;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

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
                return IterationResult.converged(round);
            }
            String submittedSummary = null;
            for (ToolCall call : result.toolCalls()) {
                messages.add(ChatMessage.assistant(null, Collections.singletonList(call)));
                if (SUBMIT_STEP.equals(call.name())) {
                    submittedSummary = summaryOf(call);
                    messages.add(submit(session, call, submittedSummary));
                    continue;
                }
                messages.add(executeTool(session, call));
            }
            if (submittedSummary != null) {
                return IterationResult.submitted(submittedSummary, round);
            }
        }
        return IterationResult.notConverged(policy.maxReActRounds());
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
    private ChatMessage submit(Session session, ToolCall call, String summary) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), null,
                System.currentTimeMillis());
        commitAppend(session, request);
        Record result = session.appendRecord(RecordRole.TOOL_RESULT, "已登记，等待验收", null,
                System.currentTimeMillis());
        commitAppend(session, result);
        return ChatMessage.toolResult(call.callId(), "已登记，等待验收");
    }

    private static String summaryOf(ToolCall call) {
        Object summary = Json.parseObject(call.argumentsJson()).get("summary");
        return summary == null ? "" : String.valueOf(summary);
    }

    private ChatMessage executeTool(Session session, ToolCall call) {
        Record request = session.appendRecord(RecordRole.TOOL_REQUEST, describe(call), null,
                System.currentTimeMillis());
        commitAppend(session, request);

        Map<String, Object> args = Json.parseObject(call.argumentsJson());
        ToolExecOutcome outcome = toolExecutor.execute(session, call.name(), args, request.id());

        Record result = session.appendRecord(RecordRole.TOOL_RESULT, outcome.content(),
                new ToolResultPayload(outcome.isTruncated(), outcome.resourceUri()), System.currentTimeMillis());
        commitAppend(session, result);
        if (LOAD_SKILL.equals(call.name())) {
            // SOP 注入为 SYSTEM 记录：此后不参与滑窗（§7.3 规则 ③）
            Record injected = session.appendRecord(RecordRole.SYSTEM, outcome.content(), null,
                    System.currentTimeMillis());
            commitAppend(session, injected);
        }
        return ChatMessage.toolResult(call.callId(), outcome.content());
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
     * 流水 → 消息：ASSISTANT/TOOL_REQUEST/TOOL_RESULT 都按角色回放；
     * TOOL_REQUEST 的 content 是单行 JSON，能恢复到 assistant 的工具调用形态。
     */
    private static List<ChatMessage> toMessages(ContextWindow window) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (Record record : window.getRecords()) {
            if (record.role() == RecordRole.SYSTEM || record.role() == RecordRole.SUMMARY) {
                continue;
            }
            switch (record.role()) {
                case ASSISTANT:
                    messages.add(ChatMessage.of(RecordRole.ASSISTANT, record.content()));
                    break;
                case TOOL_REQUEST:
                    messages.add(ChatMessage.assistant(null, parseCall(record.content())));
                    break;
                case TOOL_RESULT:
                    messages.add(ChatMessage.of(RecordRole.TOOL_RESULT, record.content()));
                    break;
                default:
                    messages.add(ChatMessage.of(record.role(), record.content()));
            }
        }
        return messages;
    }

    private static List<ToolCall> parseCall(String content) {
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
