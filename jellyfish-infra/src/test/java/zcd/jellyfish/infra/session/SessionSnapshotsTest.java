package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionTodoSnapshot;
import zcd.jellyfish.api.extension.SessionToolCallSnapshot;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.Arrays;

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
        assertEquals(PermissionMode.PLAN, snapshot.getPermissionMode());
        assertEquals(4, snapshot.getMessages().size());
        assertEquals(1, snapshot.getTodos().size());
        assertNotNull(snapshot.getUsage());
        // 每条消息都算一次调用计数，未返回用量的消息也计入
        assertEquals(4L, snapshot.getUsage().getLlmCalls());
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
        assertEquals(4L, restored.getUsage().getLlmCalls());
        LlmUsage messageUsage = restored.getMessages().get(1).getUsage();
        assertNotNull(messageUsage);
        assertEquals(7, messageUsage.getPromptTokens());
        assertEquals(8, messageUsage.getCompletionTokens());
        assertEquals(15, messageUsage.getTotalTokens());
    }

    @Test
    @DisplayName("未返回用量的消息回放后用量仍为空，不能变成零用量")
    void restore_should_keepMissingUsageAsNull() {
        Session restored = Session.restore(SessionSnapshots.capture(fullSession()));

        assertNull(restored.getMessages().get(0).getUsage());
    }

    @Test
    @DisplayName("待办状态与编号都应保留")
    void restore_should_keepTodoState() {
        Session original = fullSession();

        Session restored = Session.restore(SessionSnapshots.capture(original));

        assertEquals(1, restored.getTodos().size());
        PendingTodo todo = restored.getTodos().get(0);
        assertEquals("1", todo.getId());
        assertEquals("写测试", todo.getContent());
        assertEquals(PendingTodo.Status.DONE, todo.getStatus());
        assertEquals(original.getTodos().get(0).getCreatedAt(), todo.getCreatedAt());
    }

    @Test
    @DisplayName("回放后新增待办不得复用已占用的编号")
    void restore_should_advanceTodoSequence() {
        SessionSnapshot snapshot = SessionSnapshots.capture(fullSession());
        Session restored = Session.restore(snapshot);

        PendingTodo next = restored.addTodo("再来一条");

        assertEquals("2", next.getId());
    }

    @Test
    @DisplayName("为空的标题与 agent 必须保持为空，不能被填成默认值")
    void restore_should_keepNullFields_when_absent() {
        Session session = new Session("session-2", null, null, null, null, CREATED_AT);

        Session restored = Session.restore(SessionSnapshots.capture(session));

        assertNull(restored.getTitle());
        assertNull(restored.getAgentId());
        assertNull(restored.getProvider());
        assertNull(restored.getModel());
        assertEquals(PermissionMode.NORMAL, restored.getPermissionMode());
        assertEquals(0, restored.size());
        assertEquals(0, restored.getTodos().size());
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
        Session session = new Session("session-1", "coder", "openai", "gpt-4o", PermissionMode.PLAN, CREATED_AT);
        session.setTitle("标题");
        session.append(SessionMessage.of(LlmMessage.user("你好")));
        session.append(SessionMessage.of(
                LlmMessage.assistant("我来读文件", Arrays.asList(new LlmToolCall(0, "call-1", "read_file",
                        "{\"path\":\"a.txt\"}"))),
                new LlmUsage(7, 8, 15)));
        session.append(SessionMessage.of(LlmMessage.tool("call-1", "read_file", "文件内容")));
        session.append(SessionMessage.of(LlmMessage.assistant("读完了")));
        session.addTodo("写测试");
        session.completeTodo("1");
        // addTodo 会刷新 updatedAt，这里再改一次标题以确保 updatedAt 与 createdAt 不同
        session.setTitle("标题");
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
        assertEquals(expected.getPermissionMode(), actual.getPermissionMode());
        assertEquals(expected.getUsage().getPromptTokens(), actual.getUsage().getPromptTokens());
        assertEquals(expected.getUsage().getCompletionTokens(), actual.getUsage().getCompletionTokens());
        assertEquals(expected.getUsage().getTotalTokens(), actual.getUsage().getTotalTokens());
        assertEquals(expected.getUsage().getLlmCalls(), actual.getUsage().getLlmCalls());
        assertEquals(expected.getMessages().size(), actual.getMessages().size());
        for (int i = 0; i < expected.getMessages().size(); i++) {
            assertMessageEquals(expected.getMessages().get(i), actual.getMessages().get(i));
        }
        assertEquals(expected.getTodos().size(), actual.getTodos().size());
        for (int i = 0; i < expected.getTodos().size(); i++) {
            assertTodoEquals(expected.getTodos().get(i), actual.getTodos().get(i));
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

    /**
     * 逐字段比对两条待办快照。
     *
     * @param expected 期望快照
     * @param actual   实际快照
     */
    private static void assertTodoEquals(SessionTodoSnapshot expected, SessionTodoSnapshot actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getContent(), actual.getContent());
        assertEquals(expected.isDone(), actual.isDone());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    }
}
