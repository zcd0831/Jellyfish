package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 折叠 = 把窗口变短：覆盖边界写进 SUMMARY，摘要素材不含 reasoning。规则出处：模块-感知 §8.2。 */
class ContextWindowServiceTest {

    private static final TokenEstimator CHARS = new TokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }
    };

    private final List<ModelInvocationRequest> requests = new ArrayList<ModelInvocationRequest>();
    private final ModelInvocationPort model = new ModelInvocationPort() {
        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            requests.add(request);
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
        return new ContextWindowService(model, policies, commitScope, events -> { }, event -> {
            if (event.kind() == io.github.zcd0831.jellyfish.application.shared.ProgressEvent.Kind.CONTEXT_USAGE) {
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
        ContextWindowService subject = service();

        subject.ensureWithinLimit(session);

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
        policy = tightPolicy(10, 20);
        Session session = sessionWith(6);
        // 折叠前读一次（有观测），折叠清掉后再读一次（没了）
        when(budget.budgetOf(any(Session.class))).thenReturn(observed(60L, false), (io.github.zcd0831.jellyfish.domain.action.budget.Budget) null);
        ContextWindowService subject = service();

        subject.ensureWithinLimit(session);

        org.mockito.Mockito.verify(budget).forgetPromptObservation(session);
        assertEquals("— / 1k", events.get(events.size() - 1), "旧数值不再描述当前窗口");
    }

    private static Session sessionWith(int userRecords) {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        for (int i = 0; i < userRecords; i++) {
            session.appendRecord(RecordRole.USER, "0123456789", null, i);
        }
        return session;
    }

    private static RuntimePolicy tightPolicy(int recordCount, int threshold) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("contextWindowRecordCount", recordCount);
        values.put("summarizationTokenThreshold", threshold);
        values.put("inputSafetyMargin", 0);
        values.put("systemRecordRatioLimit", 0.9);
        return RuntimePolicy.defaults().adjust(values);
    }

    @Test
    void 折叠把窗口真的变短且记下覆盖边界() {
        policy = tightPolicy(10, 20);
        Session session = sessionWith(6);
        ContextWindowService subject = service();
        ContextWindow before = subject.currentWindow(session);
        assertEquals(60, before.getUsage().getUsedTokens());

        subject.ensureWithinLimit(session);

        Record summary = session.records().get(session.records().size() - 1);
        assertEquals(RecordRole.SUMMARY, summary.role());
        assertEquals(4, summary.summaryCoversUpTo(), "60 字里折掉 4 轮才回到 20 上限以内");
        assertEquals(1, subject.currentWindow(session).getRecords().stream()
                .filter(r -> r.role() == RecordRole.SUMMARY).count());
        assertEquals(2, subject.currentWindow(session).getRecords().size()
                - 1, "窗口里只剩两轮未被覆盖的记录");
    }

    @Test
    void 折叠之间至少保留一轮不折() {
        policy = tightPolicy(10, 1);
        Session session = sessionWith(3);

        service().ensureWithinLimit(session);

        Record summary = session.records().get(session.records().size() - 1);
        assertEquals(RecordRole.SUMMARY, summary.role());
        assertEquals(2, summary.summaryCoversUpTo(), "只剩一轮必须留着，不能把当前对话折没");
    }

    @Test
    void 摘要素材不含reasoning() {
        policy = tightPolicy(10, 20);
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        session.appendRecord(RecordRole.ASSISTANT, "看一下", null, "t1", "m1", null, 1L);
        session.appendRecord(RecordRole.TOOL_REQUEST,
                "{\"callId\":\"c1\",\"name\":\"list_dir\",\"arguments\":\"{}\"}", "内部推理过程", "t1", "m1",
                null, 2L);
        session.appendRecord(RecordRole.TOOL_RESULT, "0123456789012345678", null, "t1", "m1", null, 3L);
        session.appendRecord(RecordRole.USER, "0123456789", null, 4L);

        service().ensureWithinLimit(session);

        assertFalse(requests.isEmpty(), "超限应触发一次摘要调用");
        String material = requests.get(0).messages().get(0).content();
        assertFalse(material.contains("内部推理过程"), material);
        assertTrue(material.contains("list_dir"), material);
    }

    @Test
    void 没有可折的轮就不调用模型() {
        policy = tightPolicy(10, 20);
        Session session = sessionWith(1);

        service().ensureWithinLimit(session);

        assertTrue(requests.isEmpty());
    }
}
