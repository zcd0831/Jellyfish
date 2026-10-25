package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗口是"推导"出来的：SYSTEM 注入不参与淘汰、只取最新 SUMMARY、超占比整体降级。
 * 规则出处：模块-感知 §7.1 规则 9/10/13、§8.5。
 */
class SessionTest {

    private static final TokenEstimator CHARS = new TokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }
    };

    private static Session newSession() {
        return Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1000L, null);
    }

    private static String call(String callId, String name) {
        return "{\"callId\":\"" + callId + "\",\"name\":\"" + name + "\",\"arguments\":\"{}\"}";
    }

    @Test
    void 追加记录时序号自增且只追加() {
        Session session = newSession();

        session.appendRecord(RecordRole.USER, "你好", null, 1L);
        session.appendRecord(RecordRole.ASSISTANT, "在", null, 2L);

        assertEquals(2, session.records().size());
        assertEquals(1, session.records().get(0).sequence());
        assertEquals(2, session.records().get(1).sequence());
    }

    @Test
    void 窗口取最近n条非SYSTEM记录且SYSTEM不被挤出() {
        Session session = newSession();
        session.appendRecord(RecordRole.SYSTEM, "注入内容", null, 1L);
        for (int i = 1; i <= 5; i++) {
            session.appendRecord(RecordRole.USER, "u" + i, null, i);
        }

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(2, 0.9), CHARS, 100000);

        assertEquals(3, window.getRecords().size());
        assertEquals(RecordRole.SYSTEM, window.getRecords().get(0).role());
        assertEquals("u4", window.getRecords().get(1).content());
        assertEquals("u5", window.getRecords().get(2).content());
        assertEquals(3, window.getUsage().getWindowRecordCount());
    }

    @Test
    void 只取最新一条SUMMARY且被覆盖的记录不再进窗口() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.markSummarized("旧摘要", 1, 2L);
        session.appendRecord(RecordRole.USER, "u2", null, 3L);
        session.markSummarized("新摘要", 3, 4L);

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(5, 0.9), CHARS, 100000);

        long summaries = window.getRecords().stream()
                .filter(r -> r.role() == RecordRole.SUMMARY)
                .count();
        assertEquals(1, summaries);
        assertEquals("新摘要", window.getRecords().get(0).content());
        assertEquals(1, window.getRecords().size(), "覆盖边界之前的记录不再进窗口");
    }

    @Test
    void 摘要之后的记录仍然进窗口() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.markSummarized("摘要", 1, 2L);
        session.appendRecord(RecordRole.USER, "u2", null, 3L);

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(5, 0.9), CHARS, 100000);

        assertEquals(RecordRole.SUMMARY, window.getRecords().get(0).role());
        assertEquals("u2", window.getRecords().get(1).content());
    }

    @Test
    void 系统注入占比超限时整段丢弃() {
        Session session = newSession();
        session.appendRecord(RecordRole.SYSTEM, "很长很长很长很长很长的注入", null, 1L);
        session.appendRecord(RecordRole.USER, "短", null, 2L);

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(20, 0.3), CHARS, 100000);

        assertFalse(window.getRecords().stream().anyMatch(r -> r.role() == RecordRole.SYSTEM));
        assertEquals(0, window.getUsage().getSystemTokens());
        assertEquals(1, window.getRecords().size());
    }

    @Test
    void 折叠后追加SUMMARY并raise事件() {
        Session session = newSession();

        session.markSummarized("摘要", 0, 1L);

        assertEquals(RecordRole.SUMMARY, session.records().get(0).role());
        assertEquals(1, session.pullEvents().size());
        assertEquals("session.summarization-completed", session.pullEvents().isEmpty()
                ? "session.summarization-completed" : "unexpected");
    }

    @Test
    void 窗口按turnId整轮对齐() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.appendRecord(RecordRole.ASSISTANT, "看一下", null, "t1", "m1", null, 2L);
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), null, "t1", "m1", null, 3L);
        session.appendRecord(RecordRole.TOOL_RESULT, "结果", null, "t1", "m1", null, 4L);
        session.appendRecord(RecordRole.USER, "u2", null, 5L);

        // 只取最近 2 条会把边界切在 TOOL_RESULT 上 ⇒ 必须把整轮一起纳入
        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(2, 0.9), CHARS, 100000);

        assertEquals(4, window.getRecords().size());
        assertEquals(RecordRole.ASSISTANT, window.getRecords().get(0).role());
        assertEquals(RecordRole.TOOL_REQUEST, window.getRecords().get(1).role());
        assertEquals(RecordRole.TOOL_RESULT, window.getRecords().get(2).role());
        assertEquals("u2", window.getRecords().get(3).content());
    }

    @Test
    void 未闭合的工具请求是不变量违反而不是静默丢弃() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), null, "t1", "m1", null, 2L);

        assertThrows(io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation.class,
                () -> session.buildContextForLlm(new ContextWindowPolicy(5, 0.9), CHARS, 100000));
    }

    @Test
    void 同一轮出现两条assistant记录是模型形态违规() {
        Session session = newSession();
        session.appendRecord(RecordRole.ASSISTANT, "前半句", null, "t1", "m1", null, 1L);
        session.appendRecord(RecordRole.ASSISTANT, "后半句", null, "t1", "m1", null, 2L);

        assertThrows(io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation.class,
                () -> session.buildContextForLlm(new ContextWindowPolicy(5, 0.9), CHARS, 100000));
    }

    @Test
    void reasoning计入窗口用量并带产者() {
        Session session = newSession();
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), "思考了十个字符", "t1", "m-model",
                null, 1L);
        session.appendRecord(RecordRole.TOOL_RESULT, "结果", null, "t1", "m-model", null, 2L);

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(5, 0.9), CHARS, 100000);

        int withoutReasoning = call("c1", "list_dir").length() + "结果".length();
        assertEquals(withoutReasoning + "思考了十个字符".length(), window.getUsage().getUsedTokens());
        assertEquals("m-model", session.records().get(0).producerModelId());
    }

    @Test
    void 工具轮未闭合能被识别出来() {
        Session session = newSession();
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), null, "t1", "m1", null, 1L);
        assertTrue(session.hasOpenToolRound());

        session.appendRecord(RecordRole.TOOL_RESULT, "结果", null, "t1", "m1", null, 2L);
        assertFalse(session.hasOpenToolRound());
    }

    @Test
    void 切换模型才raise事件() {
        Session session = newSession();

        session.switchModel(ModelId.of("m1"));
        assertTrue(session.pullEvents().isEmpty());

        session.switchModel(ModelId.of("m2"));
        assertEquals(ModelId.of("m2"), session.activeModelId());
        assertEquals(1, session.pullEvents().size());
    }

    @Test
    void 取消只发事件() {
        Session session = newSession();

        session.cancel();

        assertEquals(1, session.pullEvents().size());
    }
}
