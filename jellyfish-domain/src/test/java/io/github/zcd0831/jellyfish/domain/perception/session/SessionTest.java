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
 * 窗口是"推导"出来的：最新 SUMMARY + 覆盖边界之后的全部记录（含 SYSTEM 注入）。
 * 规则出处：04-perception §4.8.1 规则 9/10/14、§4.9.5。
 */
class SessionTest {

    private static final TokenEstimator CHARS = new TokenEstimator() {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }
    };

    /** 创建本身会 raise SessionStarted（04-perception §4.7）：各用例从“创建后”起算事件。 */
    private static Session newSession() {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1000L, null);
        session.pullEvents();
        return session;
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
    void 窗口是覆盖边界之后的全部记录且含SYSTEM注入() {
        Session session = newSession();
        session.appendRecord(RecordRole.SYSTEM, "注入内容", null, 1L);
        for (int i = 1; i <= 5; i++) {
            session.appendRecord(RecordRole.USER, "u" + i, null, i);
        }

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

        assertEquals(6, window.getRecords().size(), "没有摘要时一条不丢");
        assertEquals(RecordRole.SYSTEM, window.getRecords().get(0).role());
        assertEquals("u5", window.getRecords().get(5).content());
        assertEquals(4, window.getUsage().getSystemTokens());
        assertEquals(6, window.getUsage().getWindowRecordCount());
    }

    @Test
    void 只取最新一条SUMMARY且被覆盖的记录不再进窗口() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.markSummarized("旧摘要", 1, 2L);
        session.appendRecord(RecordRole.USER, "u2", null, 3L);
        session.markSummarized("新摘要", 3, 4L);

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

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

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

        assertEquals(RecordRole.SUMMARY, window.getRecords().get(0).role());
        assertEquals("u2", window.getRecords().get(1).content());
    }

    @Test
    void 折叠后SYSTEM注入随覆盖边界一起退出窗口() {
        Session session = newSession();
        session.appendRecord(RecordRole.SYSTEM, "旧注入", null, 1L);
        session.appendRecord(RecordRole.USER, "u1", null, 2L);
        session.markSummarized("摘要", 2, 3L);
        session.appendRecord(RecordRole.SYSTEM, "新注入", null, 4L);

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

        assertEquals(2, window.getRecords().size(), "只保留摘要与边界之后的注入");
        assertEquals("新注入", window.getRecords().get(1).content());
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
    void 窗口按turnId整轮归位() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.appendRecord(RecordRole.ASSISTANT, "看一下", null, "t1", "m1", null, 2L);
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), null, "t1", "m1", null, 3L);
        session.appendRecord(RecordRole.TOOL_RESULT, "结果", null, "t1", "m1", null, 4L);
        session.appendRecord(RecordRole.USER, "u2", null, 5L);

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

        assertEquals(5, window.getRecords().size());
        assertEquals(3, session.turnsAfter(0).size(), "工具轮合成一轮，USER 各自成轮");
    }

    @Test
    void 未闭合的工具请求是不变量违反而不是静默丢弃() {
        Session session = newSession();
        session.appendRecord(RecordRole.USER, "u1", null, 1L);
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), null, "t1", "m1", null, 2L);

        assertThrows(io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation.class,
                () -> session.buildContextForLlm(CHARS, 100000));
    }

    @Test
    void 同一轮出现两条assistant记录是模型形态违规() {
        Session session = newSession();
        session.appendRecord(RecordRole.ASSISTANT, "前半句", null, "t1", "m1", null, 1L);
        session.appendRecord(RecordRole.ASSISTANT, "后半句", null, "t1", "m1", null, 2L);

        assertThrows(io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation.class,
                () -> session.buildContextForLlm(CHARS, 100000));
    }

    @Test
    void reasoning计入窗口用量并带产者() {
        Session session = newSession();
        session.appendRecord(RecordRole.TOOL_REQUEST, call("c1", "list_dir"), "思考了十个字符", "t1", "m-model",
                null, 1L);
        session.appendRecord(RecordRole.TOOL_RESULT, "结果", null, "t1", "m-model", null, 2L);

        ContextWindow window = session.buildContextForLlm(CHARS, 100000);

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
    void 创建即raiseSessionStarted而重建不重发() {
        Session created = Session.create(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), 1000L, null);
        List<io.github.zcd0831.jellyfish.domain.shared.DomainEvent> events = created.pullEvents();
        assertEquals(1, events.size());
        assertEquals("session.started", events.get(0).name());

        Session rehydrated = Session.rehydrate(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), "标题", 1000L, java.util.Collections.<Record>emptyList(), null);
        assertTrue(rehydrated.pullEvents().isEmpty(), "续跑不重发 SessionStarted（§4.8.1 规则 7）");
    }

    @Test
    void 取消只发事件() {
        Session session = newSession();

        session.cancel();

        assertEquals(1, session.pullEvents().size());
    }
}
