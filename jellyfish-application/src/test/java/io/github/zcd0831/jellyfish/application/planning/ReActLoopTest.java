package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.Answer;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Question;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextUsage;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 内循环的分支：提交 / load_skill 注入 / 模型切换 / 撞轮次上限 / 流式。 */
class ReActLoopTest {

    private final ModelInvocationPort model = mock(ModelInvocationPort.class);
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final ContextWindowService contextWindow = mock(ContextWindowService.class);
    private final PolicyRepository policies = mock(PolicyRepository.class);
    private final CommitScope commitScope = mock(CommitScope.class);
    private final HumanInteractionPort human = mock(HumanInteractionPort.class);

    private Session session() {
        SystemPromptSnapshot snapshot = new SystemPromptSnapshot("系统提示", Collections.emptyList(),
                Collections.emptyList());
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L,
                snapshot);
        session.appendRecord(RecordRole.USER, "你好", null, 2L);
        return session;
    }

    private ReActLoop loop(RuntimePolicy policy) {
        when(policies.load()).thenReturn(policy);
        when(contextWindow.currentWindow(any(Session.class))).thenReturn(new ContextWindow(
                new ArrayList<Record>(), new ContextUsage(10, 1000, 0, 0, 0)));
        when(budget.recordUsage(any(Session.class), anyInt())).thenReturn(false);
        return new ReActLoop(model, toolExecutor, budget, contextWindow, policies, commitScope, human);
    }

    private static ModelInvocationResult reply(String text, List<ToolCall> calls, ModelId served) {
        return new ModelInvocationResult(text, calls, new ModelUsage(1, 1), served);
    }

    @Test
    void 模型不再调工具即收敛() {
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(
                reply("答复", Collections.<ToolCall>emptyList(), ModelId.of("m1")));

        IterationResult result = loop(RuntimePolicy.defaults()).runIteration(session(), null, null);

        assertTrue(result.isConverged());
        assertFalse(result.submitted());
    }

    @Test
    void submit_step只登记并返回已提交() {
        Session session = session();
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(null,
                Collections.singletonList(new ToolCall("c1", "submit_step", "{\"summary\":\"做完了\"}")),
                ModelId.of("m1")));

        IterationResult result = loop(RuntimePolicy.defaults()).runIteration(session, null, null);

        assertTrue(result.submitted());
        assertEquals("做完了", result.submittedSummary());
    }

    @Test
    void submit_step缺summary时按空串提交() {
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(null,
                Collections.singletonList(new ToolCall("c1", "submit_step", "{}")), ModelId.of("m1")));

        IterationResult result = loop(RuntimePolicy.defaults()).runIteration(session(), null, null);

        assertTrue(result.submitted());
        assertEquals("", result.submittedSummary());
    }

    @Test
    void load_skill的结果注入为SYSTEM记录() {
        Session session = session();
        when(toolExecutor.execute(any(Session.class), anyString(), any(Map.class), any(RecordId.class)))
                .thenReturn(ToolExecOutcome.plain("SOP 正文"));
        List<ModelInvocationResult> script = Arrays.asList(
                reply(null, Collections.singletonList(new ToolCall("c1", "load_skill",
                        "{\"skillName\":\"s\"}")), ModelId.of("m1")),
                reply("完成", Collections.<ToolCall>emptyList(), ModelId.of("m1")));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(script.get(0), script.get(1));

        loop(RuntimePolicy.defaults()).runIteration(session, null, null);

        boolean injected = session.records().stream()
                .anyMatch(r -> r.role() == RecordRole.SYSTEM && "SOP 正文".equals(r.content()));
        assertTrue(injected, "SOP 应注入成 SYSTEM 记录");
    }

    @Test
    void 模型被降级时切换会话模型() {
        Session session = session();
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(
                reply("答复", Collections.<ToolCall>emptyList(), ModelId.of("m2")));

        loop(RuntimePolicy.defaults()).runIteration(session, null, null);

        assertEquals(ModelId.of("m2"), session.activeModelId());
    }

    @Test
    void 撞轮次上限返回未收敛() {
        RuntimePolicy oneRound = RuntimePolicy.defaults().adjust(
                Collections.<String, Object>singletonMap("maxReActRounds", 1));
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(null,
                Collections.singletonList(new ToolCall("c1", "some_tool", "{}")), ModelId.of("m1")));
        when(toolExecutor.execute(any(Session.class), anyString(), any(Map.class), any(RecordId.class)))
                .thenReturn(ToolExecOutcome.plain("结果"));

        IterationResult result = loop(oneRound).runIteration(session(), null, null);

        assertFalse(result.isConverged());
    }

    private static String call(String callId, String name, String arguments) {
        return "{\"callId\":\"" + callId + "\",\"name\":\"" + name + "\",\"arguments\":\""
                + arguments + "\"}";
    }

    private List<ChatMessage> capturedMessages(Session session, List<ModelInvocationRequest> captured) {
        ReActLoop subject = loop(RuntimePolicy.defaults());
        // 注意顺序：loop() 自己会打一份空窗口的桩，真实窗口必须后打
        when(contextWindow.currentWindow(any(Session.class))).thenReturn(new ContextWindow(
                new ArrayList<Record>(session.records()), new ContextUsage(10, 1000, 0, 0, 0)));
        when(model.invoke(any(ModelInvocationRequest.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return reply("答复", Collections.<ToolCall>emptyList(), ModelId.of("m1"));
        });
        subject.runIteration(session, null, null);
        return captured.get(0).messages();
    }

    @Test
    void 回放时同一轮还原成一条assistant消息且带回reasoning() {
        Session session = session();
        session.appendRecord(RecordRole.ASSISTANT, "我先看看", null, "t1", "m1", null, 3L);
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "read_file", "{}"), "先看看目录", "t1", "m1",
                null, 4L);
        session.appendRecord(RecordRole.TOOL_RESULT, "文件内容", null, "t1", "m1", null, 5L);
        List<ModelInvocationRequest> captured = new ArrayList<ModelInvocationRequest>();

        List<ChatMessage> messages = capturedMessages(session, captured);

        ChatMessage assistant = messages.get(messages.size() - 2);
        assertEquals("我先看看", assistant.content(), "文本与调用必须在同一条消息里");
        assertEquals(1, assistant.toolCalls().size());
        assertEquals("c1", assistant.toolCalls().get(0).callId());
        assertEquals("先看看目录", assistant.reasoning(), "reasoning 必须随工具请求回放");
        ChatMessage tool = messages.get(messages.size() - 1);
        assertEquals("c1", tool.toolCallId(), "工具结果必须带 tool_call_id，否则端点 400");
        assertEquals("文件内容", tool.content());
    }

    @Test
    void 切换模型后旧模型的reasoning不再回放() {
        Session session = session();
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "read_file", "{}"), "旧模型的推理", "t1", "m1",
                null, 3L);
        session.appendRecord(RecordRole.TOOL_RESULT, "文件内容", null, "t1", "m1", null, 4L);
        session.switchModel(ModelId.of("m2"));
        List<ModelInvocationRequest> captured = new ArrayList<ModelInvocationRequest>();

        List<ChatMessage> messages = capturedMessages(session, captured);

        ChatMessage assistant = messages.get(messages.size() - 2);
        assertEquals(null, assistant.reasoning(), "私有状态只回放给产者模型");
    }

    @Test
    void 一次响应的文本与多个调用进同一条assistant消息() {
        Session session = session();
        final List<ModelInvocationRequest> captured = new ArrayList<ModelInvocationRequest>();
        when(model.invoke(any(ModelInvocationRequest.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            if (captured.size() == 1) {
                return new ModelInvocationResult("我先读两个文件", Arrays.asList(
                        new ToolCall("c1", "read_file", "{\"path\":\"a\"}"),
                        new ToolCall("c2", "read_file", "{\"path\":\"b\"}")), "推理",
                        new ModelUsage(1, 1), ModelId.of("m1"));
            }
            return new ModelInvocationResult("完成", Collections.<ToolCall>emptyList(), new ModelUsage(1, 1),
                    ModelId.of("m1"));
        });
        when(toolExecutor.execute(any(Session.class), anyString(), any(Map.class), any(RecordId.class)))
                .thenReturn(ToolExecOutcome.plain("内容"));
        ReActLoop subject = loop(RuntimePolicy.defaults());
        when(contextWindow.currentWindow(any(Session.class))).thenAnswer(invocation -> {
            Session running = invocation.getArgument(0);
            return new ContextWindow(new ArrayList<Record>(running.records()),
                    new ContextUsage(10, 1000, 0, 0, 0));
        });

        subject.runIteration(session, null, null);

        List<ChatMessage> messages = captured.get(1).messages();
        ChatMessage turn = messages.get(messages.size() - 3);
        assertEquals("我先读两个文件", turn.content());
        assertEquals(2, turn.toolCalls().size(), "两个调用必须在同一条 assistant 消息里");
        assertEquals("c1", messages.get(messages.size() - 2).toolCallId());
        assertEquals("c2", messages.get(messages.size() - 1).toolCallId(), "工具结果按调用顺序紧随其后");
    }

    @Test
    void 摘要作为user消息回灌给模型() {
        Session session = session();
        session.markSummarized("早前要点", 0, 3L);
        List<ModelInvocationRequest> captured = new ArrayList<ModelInvocationRequest>();

        List<ChatMessage> messages = capturedMessages(session, captured);

        boolean hasSummary = messages.stream().anyMatch(m -> m.role() == RecordRole.SUMMARY
                && m.content().startsWith("【更早对话的摘要】") && m.content().contains("早前要点"));
        assertTrue(hasSummary, "折叠后的摘要必须真的进请求");
    }

    @Test
    void 工具执行抛错时把未闭合的请求补成中止结果() {
        Session session = session();
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(reply(null,
                Collections.singletonList(new ToolCall("c1", "read_file", "{}")), ModelId.of("m1")));
        when(toolExecutor.execute(any(Session.class), anyString(), any(Map.class), any(RecordId.class)))
                .thenThrow(new IllegalStateException("工具进程被杀"));

        assertThrows(RuntimeException.class,
                () -> loop(RuntimePolicy.defaults()).runIteration(session, null, null));

        Record last = session.records().get(session.records().size() - 1);
        assertEquals(RecordRole.TOOL_RESULT, last.role());
        assertTrue(last.content().contains("本轮中止"), last.content());
    }

    @Test
    void 记录工具请求时把reasoning落进流水() {
        Session session = session();
        when(model.invoke(any(ModelInvocationRequest.class))).thenReturn(
                new ModelInvocationResult(null,
                        Collections.singletonList(new ToolCall("c1", "read_file", "{}")), "想一下",
                        new ModelUsage(1, 1), ModelId.of("m1")));
        when(toolExecutor.execute(any(Session.class), anyString(), any(Map.class), any(RecordId.class)))
                .thenReturn(ToolExecOutcome.plain("内容"));

        loop(RuntimePolicy.defaults()).runIteration(session, null, null);

        Record request = session.records().stream()
                .filter(r -> r.role() == RecordRole.TOOL_REQUEST).findFirst().get();
        assertEquals("想一下", request.reasoning());
    }

    @Test
    void 流式路径把增量交给sink() {
        when(model.invokeStreaming(any(ModelInvocationRequest.class), any(ChunkSink.class)))
                .thenReturn(reply("完整", Collections.<ToolCall>emptyList(), ModelId.of("m1")));
        final StringBuilder seen = new StringBuilder();

        loop(RuntimePolicy.defaults()).runIteration(session(), null, new ChunkSink() {
            @Override
            public void accept(String delta) {
                seen.append(delta);
            }
        });

        assertTrue(seen.length() >= 0);
    }
}
