package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionToolCallSnapshot;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SessionSnapshots} 与 {@link Session#restore(SessionSnapshot)} 的保真性测试。
 * <p>
 * <b>这是快照契约唯一的护栏</b>：快照是插件落盘的全部依据，「字段漏了一个」不会让任何编译失败，
 * 只会在某次重启后悄悄少一段历史。因此这里逐字段比对
 * {@code capture → restore → capture} 的两份快照必须完全相等。
 *
 * @author zcd
 */
@DisplayName("会话快照往返保真")
class SessionSnapshotsTest {

    /** 会话创建时间戳。 */
    private static final long CREATED_AT = 1_700_000_000_000L;

    @Test
    @DisplayName("capture 应把会话的全部字段投影出来")
    void capture_should_projectEveryField() {
        Session session = fullSession();

        SessionSnapshot snapshot = SessionSnapshots.capture(session);

        assertEquals("session-1", snapshot.getSessionId());
        assertEquals(CREATED_AT, snapshot.getCreatedAt());
        assertEquals("标题", snapshot.getTitle());
        assertEquals("coder", snapshot.getAgentId());
        assertEquals("openai", snapshot.getProvider());
        assertEquals("gpt-4o", snapshot.getModel());
        assertEquals(4, snapshot.getMessages().size());
        assertNotNull(snapshot.getUsage());
        // 调用次数只认 assistant 消息：user 输入与 tool 结果不计，未返回用量的 assistant 仍计一次
        assertEquals(2L, snapshot.getUsage().getLlmCalls());
    }

    @Test
    @DisplayName("往返一圈后快照必须逐字段相等")
    void restore_should_roundTrip_when_snapshotComplete() {
        SessionSnapshot first = SessionSnapshots.capture(fullSession());

        SessionSnapshot second = SessionSnapshots.capture(Session.restore(first));

        assertSnapshotEquals(first, second);
    }

    @Test
    @DisplayName("工具调用与其结果必须成对保留：丢了 id 就再也配不上")
    void restore_should_keepToolCallsAndResults() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        SessionMessage assistant = restored.getMessages().get(1);
        assertEquals(1, assistant.getMessage().getToolCalls().size());
        LlmToolCall toolCall = assistant.getMessage().getToolCalls().get(0);
        assertEquals("call-1", toolCall.getId());
        assertEquals("read_file", toolCall.getName());
        assertEquals("{\"path\":\"a.txt\"}", toolCall.getArguments());
        assertEquals("call-1", restored.getMessages().get(2).getMessage().getToolCallId());
    }

    @Test
    @DisplayName("累计用量与单次用量分属两处，都要保留")
    void restore_should_keepBothUsages() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertEquals(7L, restored.getUsage().getPromptTokens());
        assertEquals(8L, restored.getUsage().getCompletionTokens());
        assertEquals(15L, restored.getUsage().getTotalTokens());
        // 调用次数同样要往返：user / tool 消息不计，两条 assistant 各计一次
        assertEquals(2L, restored.getUsage().getLlmCalls());
        LlmUsage messageUsage = restored.getMessages().get(1).getUsage();
        assertNotNull(messageUsage);
        assertEquals(7, messageUsage.getPromptTokens());
        assertEquals(8, messageUsage.getCompletionTokens());
        assertEquals(15, messageUsage.getTotalTokens());
    }

    @Test
    @DisplayName("思考过程必须随消息一起落盘与回放：丢了就只能重新问一次")
    void restore_should_keepThinking() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertEquals("先看看 a.txt", restored.getMessages().get(1).getThinking());
    }

    @Test
    @DisplayName("压缩摘要必须随会话落盘与回放：丢了重启后模型会突然收到一整份远古历史")
    void restore_should_keepCompaction() {
        Session session = fullSession();
        session.setCompaction(new SessionCompaction("摘要正文", "m-2", 42L, 3));

        Session restored = Session.restore(SessionSnapshots.capture(session));

        assertNotNull(restored.getCompaction());
        assertEquals("摘要正文", restored.getCompaction().getSummary());
        assertEquals("m-2", restored.getCompaction().getBoundaryMessageId());
        assertEquals(42L, restored.getCompaction().getCreatedAt());
        // 丢弃条数必须一起往返：它是「哪些历史真的没了」的唯一记账，丢了就再也说不清
        assertEquals(3, restored.getCompaction().getDroppedMessageCount());
    }

    @Test
    @DisplayName("从未压缩过的会话回放后压缩仍为空，不能被填成空摘要")
    void restore_should_keepMissingCompactionAsNull() {
        Session session = new Session("session-3", null, null, null, CREATED_AT);

        Session restored = Session.restore(SessionSnapshots.capture(session));

        assertNull(restored.getCompaction());
    }

    @Test
    @DisplayName("工具结果元数据必须随会话落盘与回放：界面靠它渲染失败标记")
    void restore_should_keepToolMetadata() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertEquals(Integer.valueOf(1), restored.getMessages().get(2).getMetadata().get("exitCode"));
    }

    @Test
    @DisplayName("未产生的思考回放后仍为空，不能变成空串")
    void restore_should_keepMissingThinkingAsNull() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertNull(restored.getMessages().get(0).getThinking());
    }

    @Test
    @DisplayName("未返回用量的消息回放后用量仍为空，不能变成零用量")
    void restore_should_keepMissingUsageAsNull() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertNull(restored.getMessages().get(0).getUsage());
    }

    @Test
    @DisplayName("为空的标题与 agent 必须保持为空，不能被填成默认值")
    void restore_should_keepNullFields_when_absent() {
        Session session = new Session("session-2", null, null, null, CREATED_AT);

        Session restored = Session.restore(SessionSnapshots.capture(session));

        assertNull(restored.getTitle());
        assertNull(restored.getAgentId());
        assertNull(restored.getProvider());
        assertNull(restored.getModel());
        assertEquals(0, restored.size());
    }

    @Test
    @DisplayName("回放不得改动创建时间与最后变更时间")
    void restore_should_keepTimestamps() {
        Session original = fullSession();
        long updatedAt = original.getUpdatedAt();

        Session restored = Session.restore(SessionSnapshots.capture(original));

        assertEquals(CREATED_AT, restored.getCreatedAt());
        assertEquals(updatedAt, restored.getUpdatedAt());
    }

    /**
     * 构造一个字段尽量填满的会话。
     *
     * @return 会话运行态
     */
    private static Session fullSession() {
        Session session = new Session("session-1", "coder", "openai", "gpt-4o", CREATED_AT);
        session.setTitle("标题");
        session.append(SessionMessage.of(LlmMessage.user("你好")));
        session.append(SessionMessage.of(
                LlmMessage.assistant("我来读文件", Arrays.asList(new LlmToolCall(0, "call-1", "read_file",
                        "{\"path\":\"a.txt\"}"))),
                new LlmUsage(7, 8, 15), "先看看 a.txt"));
        session.append(SessionMessage.ofTool(LlmMessage.tool("call-1", "shell", "cwd: /x · exit: 1"),
                Collections.singletonMap(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1))));
        session.append(SessionMessage.of(LlmMessage.assistant("读完了")));
        // 再改一次标题以确保 updatedAt 与 createdAt 不同
        session.setTitle("标题");
        session.setCompaction(new SessionCompaction("早前对话的摘要", "m-2", 7L, 0));
        return session;
    }

    /**
     * 逐字段比对两份会话快照。
     *
     * @param expected 期望快照
     * @param actual   实际快照
     */
    private static void assertSnapshotEquals(SessionSnapshot expected, SessionSnapshot actual) {
        assertEquals(expected.getSessionId(), actual.getSessionId());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getAgentId(), actual.getAgentId());
        assertEquals(expected.getProvider(), actual.getProvider());
        assertEquals(expected.getModel(), actual.getModel());
        assertEquals(expected.getUsage().getPromptTokens(), actual.getUsage().getPromptTokens());
        assertEquals(expected.getUsage().getCompletionTokens(), actual.getUsage().getCompletionTokens());
        assertEquals(expected.getUsage().getTotalTokens(), actual.getUsage().getTotalTokens());
        assertEquals(expected.getUsage().getLlmCalls(), actual.getUsage().getLlmCalls());
        assertEquals(expected.getCompaction() == null, actual.getCompaction() == null);
        if (expected.getCompaction() != null) {
            assertEquals(expected.getCompaction().getSummary(), actual.getCompaction().getSummary());
            assertEquals(expected.getCompaction().getBoundaryMessageId(),
                    actual.getCompaction().getBoundaryMessageId());
            assertEquals(expected.getCompaction().getCreatedAt(), actual.getCompaction().getCreatedAt());
        }
        assertEquals(expected.getMessages().size(), actual.getMessages().size());
        for (int i = 0; i < expected.getMessages().size(); i++) {
            assertMessageEquals(expected.getMessages().get(i), actual.getMessages().get(i));
        }
    }

    /**
     * 逐字段比对两条消息快照。
     *
     * @param expected 期望快照
     * @param actual   实际快照
     */
    private static void assertMessageEquals(SessionMessageSnapshot expected, SessionMessageSnapshot actual) {
        assertEquals(expected.getMessageId(), actual.getMessageId());
        assertEquals(expected.getTimestamp(), actual.getTimestamp());
        assertEquals(expected.getRole(), actual.getRole());
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.getToolCallId(), actual.getToolCallId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getThinking(), actual.getThinking());
        // 元数据必须一起往返：丢了它，重启后界面就再也说不出「那条命令成没成」
        assertEquals(expected.getMetadata(), actual.getMetadata());
        assertEquals(expected.getUsage() == null, actual.getUsage() == null);
        if (expected.getUsage() != null) {
            assertEquals(expected.getUsage().getPromptTokens(), actual.getUsage().getPromptTokens());
            assertEquals(expected.getUsage().getCompletionTokens(), actual.getUsage().getCompletionTokens());
            assertEquals(expected.getUsage().getTotalTokens(), actual.getUsage().getTotalTokens());
        }
        assertEquals(expected.getToolCalls().size(), actual.getToolCalls().size());
        for (int i = 0; i < expected.getToolCalls().size(); i++) {
            SessionToolCallSnapshot expectedCall = expected.getToolCalls().get(i);
            SessionToolCallSnapshot actualCall = actual.getToolCalls().get(i);
            assertEquals(expectedCall.getIndex(), actualCall.getIndex());
            assertEquals(expectedCall.getId(), actualCall.getId());
            assertEquals(expectedCall.getName(), actualCall.getName());
            assertEquals(expectedCall.getArguments(), actualCall.getArguments());
        }
    }
}
