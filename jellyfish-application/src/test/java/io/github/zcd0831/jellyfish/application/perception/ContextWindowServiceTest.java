package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 折叠 = 把窗口收回到保留条数：影子主请求、两种失败、覆盖边界写进 SUMMARY。
 * 规则出处：04-perception §4.9.2、§4.8.1 规则 19/20/21。
 */
class ContextWindowServiceTest {

    private static final TokenEstimator CHARS = new TokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }
    };

    private final List<ModelInvocationRequest> requests = new ArrayList<ModelInvocationRequest>();
    private final Deque<RuntimeException> failures = new ArrayDeque<RuntimeException>();
    private final ModelInvocationPort model = new ModelInvocationPort() {
        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            requests.add(request);
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            return new ModelInvocationResult("摘要正文", Collections.emptyList(), null, ModelId.of("m1"));
        }

        @Override
        public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
            return invoke(request);
        }

        @Override
        public ModelProfile describe(ModelId modelId) {
            return new ModelProfile(1000, 100);
        }
    };

    private final CommitScope commitScope = mock(CommitScope.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final List<String> events = new ArrayList<String>();
    private RuntimePolicy policy = RuntimePolicy.defaults();

    private final PolicyRepository policies = new PolicyRepository() {
        @Override
        public RuntimePolicy load() {
            return policy;
        }

        @Override
        public void save(RuntimePolicy next) {
            policy = next;
        }
    };

    private ContextWindowService service() {
        when(budget.recordUsage(any(Session.class), any())).thenReturn(false);
        events.clear();
        requests.clear();
        return new ContextWindowService(model, policies, commitScope, events -> { }, event -> {
            if (event.kind() == ProgressEvent.Kind.CONTEXT_USAGE) {
                events.add(event.message());
            }
        }, budget, CHARS);
    }

    private static io.github.zcd0831.jellyfish.domain.action.budget.Budget observed(long tokens, boolean estimated) {
        io.github.zcd0831.jellyfish.domain.action.budget.Budget budget =
                io.github.zcd0831.jellyfish.domain.action.budget.Budget.openFor(
                        io.github.zcd0831.jellyfish.domain.shared.BudgetId.of("b1"), SessionId.of("s1"));
        budget.observePrompt(SessionId.of("s1"), tokens, estimated);
        return budget;
    }

    @Test
    void 提示符显示厂商返回的用量而不是估算() {
        Session session = sessionWith(1);
        when(budget.budgetOf(any(Session.class))).thenReturn(observed(18200L, false));

        service().ensureWithinLimit(session);

        assertEquals("18.2k / 1k", events.get(0), "厂商精确值，不带 ≈");
    }

    @Test
    void 厂商没给用量时带估算标记() {
        Session session = sessionWith(1);
        when(budget.budgetOf(any(Session.class))).thenReturn(observed(300L, true));

        service().ensureWithinLimit(session);

        assertEquals("≈300 / 1k", events.get(0));
    }

    @Test
    void 没有观测时显示占位而不是估算冒充() {
        Session session = sessionWith(1);
        when(budget.budgetOf(any(Session.class))).thenReturn(null);

        service().ensureWithinLimit(session);

        assertEquals("— / 1k", events.get(0));
    }

    @Test
    void 折叠后清掉观测并回到占位() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        when(budget.budgetOf(any(Session.class))).thenReturn(observed(60L, false),
                (io.github.zcd0831.jellyfish.domain.action.budget.Budget) null);

        service().ensureWithinLimit(session);

        verify(budget).forgetPromptObservation(session);
        assertEquals("— / 1k", events.get(events.size() - 1), "旧数值不再描述当前窗口");
    }

    @Test
    void 折叠把窗口收回到保留条数() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        ContextWindowService subject = service();
        assertEquals(60, subject.currentWindow(session).getUsage().getUsedTokens());

        subject.ensureWithinLimit(session);

        Record summary = session.records().get(session.records().size() - 1);
        assertEquals(RecordRole.SUMMARY, summary.role());
        assertEquals(4, summary.summaryCoversUpTo(), "覆盖边界落在保留区前一条");
        ContextWindow after = subject.currentWindow(session);
        assertEquals(3, after.getRecords().size(), "摘要 + 保留区两条");
        assertEquals(RecordRole.SUMMARY, after.getRecords().get(0).role());
        assertEquals("0123456789", after.getRecords().get(2).content());
    }

    @Test
    void 折叠触发点把摘要指令算进去() {
        policy = tightPolicy(1, 15);
        Session session = sessionWith(1);
        assertTrue(service().currentWindow(session).getUsage().getUsedTokens() <= 15);

        service().ensureWithinLimit(session);

        assertTrue(requests.isEmpty(), "素材本身没超限、但加指令会超 ⇒ 仍需折叠；这里只剩一轮，没有可折的");
    }

    @Test
    void 摘要素材是旧摘要加上待折区间且指令在最后() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        session.records();
        ContextWindowService subject = service();
        subject.ensureWithinLimit(session);
        int firstCovered = latestSummary(session).summaryCoversUpTo();

        policy = tightPolicy(2, 20);
        session.appendRecord(RecordRole.USER, "abcdefghij", null, 100L);
        session.appendRecord(RecordRole.USER, "klmnopqrst", null, 101L);
        session.appendRecord(RecordRole.USER, "uvwxyz0123", null, 102L);
        requests.clear();
        subject.ensureWithinLimit(session);

        assertEquals(1, requests.size());
        List<ChatMessage> messages = requests.get(0).messages();
        assertTrue(messages.get(0).content().contains("摘要正文"), "旧摘要必须作为首条素材被吞并");
        assertTrue(messages.get(0).content().contains("【更早对话的摘要】"));
        assertEquals(RecordRole.USER, messages.get(messages.size() - 1).role(), "指令追加在末尾");
        assertTrue(messages.get(messages.size() - 1).content().contains("压缩成要点"));
        assertTrue(latestSummary(session).summaryCoversUpTo() > firstCovered, "覆盖边界单调前进");
    }

    @Test
    void 摘要请求复用快照与工具快照() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        SystemPromptSnapshot snapshot = new SystemPromptSnapshot("快照提示词", Collections.emptyList(),
                Collections.emptyList());
        Session withSnapshot = Session.create(SessionId.of("s2"), null, AgentId.of("a"), ModelId.of("m1"), 1L,
                snapshot);
        for (int i = 0; i < 6; i++) {
            withSnapshot.appendRecord(RecordRole.USER, "0123456789", null, i);
        }

        service().ensureWithinLimit(withSnapshot);

        assertEquals("快照提示词", requests.get(0).systemPrompt());
        assertTrue(requests.get(0).tools().isEmpty());
    }

    @Test
    void 输入超限时丢最旧整轮并声明被丢区间() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        failures.add(new ModelInvocationException(ModelError.CONTEXT_EXCEEDED, "太长"));

        service().ensureWithinLimit(session);

        assertEquals(2, requests.size(), "超限后丢掉最旧一轮重试");
        Record summary = session.records().get(session.records().size() - 1);
        assertEquals(RecordRole.SUMMARY, summary.role());
        assertTrue(summary.content().contains("因超出输入上限未纳入摘要"), summary.content());
        assertTrue(summary.content().endsWith("摘要正文"));
        assertEquals(4, summary.summaryCoversUpTo(), "被丢区间仍算已覆盖，边界照常前进");
    }

    @Test
    void 模型不可用时上抛且不落半截摘要() {
        policy = tightPolicy(2, 20);
        Session session = sessionWith(6);
        int before = session.records().size();
        failures.add(new ModelInvocationException(ModelError.UNAVAILABLE, "挂了"));

        assertThrows(ModelInvocationException.class, () -> service().ensureWithinLimit(session));

        assertEquals(before, session.records().size());
    }

    @Test
    void 没有可折的轮就不调用模型() {
        policy = tightPolicy(10, 20);
        Session session = sessionWith(1);

        service().ensureWithinLimit(session);

        assertTrue(requests.isEmpty());
    }

    @Test
    void 摘要素材不含reasoning() {
        policy = tightPolicy(2, 20);
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        session.appendRecord(RecordRole.ASSISTANT, "看一下", null, "t1", "m1", null, 1L);
        session.appendRecord(RecordRole.TOOL_REQUEST,
                "{\"callId\":\"c1\",\"name\":\"list_dir\",\"arguments\":\"{}\"}", "内部推理过程", "t1", "m1",
                null, 2L);
        session.appendRecord(RecordRole.TOOL_RESULT, "0123456789012345678", null, "t1", "m1", null, 3L);
        session.appendRecord(RecordRole.USER, "0123456789", null, 4L);
        session.appendRecord(RecordRole.USER, "0123456789", null, 5L);

        service().ensureWithinLimit(session);

        assertFalse(requests.isEmpty(), "超限应触发一次摘要调用");
        StringBuilder material = new StringBuilder();
        for (ChatMessage message : requests.get(0).messages()) {
            material.append(message.content()).append('\n');
        }
        assertFalse(material.toString().contains("内部推理过程"), material.toString());
        boolean toolCallKept = false;
        for (ChatMessage message : requests.get(0).messages()) {
            for (io.github.zcd0831.jellyfish.application.action.port.ToolCall call : message.toolCalls()) {
                toolCallKept |= "list_dir".equals(call.name());
            }
        }
        assertTrue(toolCallKept, "工具调用按结构化形态保留，只是不带 reasoning");
    }

    private static Record latestSummary(Session session) {
        List<Record> records = session.records();
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).role() == RecordRole.SUMMARY) {
                return records.get(i);
            }
        }
        throw new IllegalStateException("没有摘要");
    }

    private static Session sessionWith(int userRecords) {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        for (int i = 0; i < userRecords; i++) {
            session.appendRecord(RecordRole.USER, "0123456789", null, i);
        }
        return session;
    }

    private static RuntimePolicy tightPolicy(int retainRecordCount, int threshold) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("collapseRetainRecordCount", retainRecordCount);
        values.put("summarizationTokenThreshold", threshold);
        values.put("inputSafetyMargin", 0);
        return RuntimePolicy.defaults().adjust(values);
    }
}
