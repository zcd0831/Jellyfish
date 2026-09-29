package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Session} 的单元测试：验证初值、消息追加、token 累计、快照不可变与各变更方法。
 * <p>
 * 会话是纯内存运行态，没有外部协作者，因此不引入 Mockito。
 *
 * @author zcd
 */
class SessionTest {

    /** 固定创建时间，避免依赖真实时钟。 */
    private static final long CREATED_AT = 1_000L;

    @Test
    void constructor_should_apply_defaults_when_optional_values_missing() {
        // When
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // Then
        assertEquals("session-1", session.getSessionId());
        assertEquals(CREATED_AT, session.getCreatedAt());
        assertEquals(CREATED_AT, session.getUpdatedAt());
        assertEquals(PermissionMode.NORMAL, session.getPermissionMode());
        assertSame(SessionUsage.EMPTY, session.getUsage());
        assertEquals(0, session.size());
        assertNull(session.getTitle());
        assertNull(session.getAgentId());
        assertNull(session.getProvider());
        assertNull(session.getModel());
    }

    @Test
    void constructor_should_keep_initial_selections() {
        // When
        Session session = new Session("session-1", "coder", "openai", "gpt-4o", PermissionMode.PLAN, CREATED_AT);

        // Then
        assertEquals("coder", session.getAgentId());
        assertEquals("openai", session.getProvider());
        assertEquals("gpt-4o", session.getModel());
        assertEquals(PermissionMode.PLAN, session.getPermissionMode());
    }

    @Test
    void getParentSessionId_should_return_null_when_root_session() {
        // When
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // Then
        assertNull(session.getParentSessionId());
        assertFalse(session.isEphemeral());
    }

    @Test
    void isEphemeral_should_return_true_when_parent_session_given() {
        // When
        Session session = new Session("session-2", "scout", null, null, null, CREATED_AT, "session-1");

        // Then
        assertEquals("session-1", session.getParentSessionId());
        assertTrue(session.isEphemeral());
    }

    @Test
    void append_should_keep_order_and_size() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When
        session.append(SessionMessage.of(LlmMessage.user("第一句")));
        int size = session.append(SessionMessage.of(LlmMessage.assistant("第二句")));

        // Then
        assertEquals(2, size);
        List<SessionMessage> messages = session.getMessages();
        assertEquals(2, messages.size());
        assertEquals(LlmMessage.ROLE_USER, messages.get(0).getRole());
        assertEquals("第一句", messages.get(0).getMessage().getContent());
        assertEquals(LlmMessage.ROLE_ASSISTANT, messages.get(1).getRole());
    }

    @Test
    void append_should_accumulate_usage_and_count_calls_without_usage() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When
        session.append(SessionMessage.of(LlmMessage.assistant("有用量"), new LlmUsage(3, 4, 7)));
        session.append(SessionMessage.of(LlmMessage.assistant("无用量")));

        // Then
        SessionUsage usage = session.getUsage();
        assertEquals(3L, usage.getPromptTokens());
        assertEquals(4L, usage.getCompletionTokens());
        assertEquals(7L, usage.getTotalTokens());
        assertEquals(2L, usage.getLlmCalls());
    }

    @Test
    void append_should_advance_updated_at() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When
        session.append(SessionMessage.of(LlmMessage.user("hi")));

        // Then：真实时钟可能在同一毫秒内，只断言「不倒退」
        assertTrue(session.getUpdatedAt() >= CREATED_AT);
    }

    @Test
    void getMessages_should_return_snapshot_not_affected_by_later_appends() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);
        session.append(SessionMessage.of(LlmMessage.user("hi")));

        // When
        List<SessionMessage> snapshot = session.getMessages();
        session.append(SessionMessage.of(LlmMessage.assistant("hello")));

        // Then
        assertEquals(1, snapshot.size());
        assertEquals(2, session.size());
    }

    @Test
    void getMessages_should_be_unmodifiable() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);
        session.append(SessionMessage.of(LlmMessage.user("hi")));

        // When / Then
        assertThrows(UnsupportedOperationException.class,
                () -> session.getMessages().add(SessionMessage.of(LlmMessage.user("again"))));
    }

    @Test
    void setTitle_should_replace_title() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When
        session.setTitle("重构会话模块");

        // Then
        assertEquals("重构会话模块", session.getTitle());
    }

    @Test
    void setAgentId_should_replace_and_allow_unbind() {
        // Given
        Session session = new Session("session-1", "coder", null, null, null, CREATED_AT);

        // When
        session.setAgentId(null);

        // Then
        assertNull(session.getAgentId());
    }

    @Test
    void setModel_should_replace_provider_and_model_together() {
        // Given
        Session session = new Session("session-1", null, "openai", "gpt-4o", null, CREATED_AT);

        // When
        session.setModel("ollama", "qwen3");

        // Then
        assertEquals("ollama", session.getProvider());
        assertEquals("qwen3", session.getModel());
    }

    @Test
    void setModel_should_treat_null_as_follow_default() {
        // Given
        Session session = new Session("session-1", null, "openai", "gpt-4o", null, CREATED_AT);

        // When
        session.setModel(null, null);

        // Then
        assertNull(session.getProvider());
        assertNull(session.getModel());
    }

    @Test
    void indexOfMessage_should_return_position_and_minusOne_when_absent() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);
        session.append(SessionMessage.of(LlmMessage.user("一")));
        session.append(SessionMessage.of(LlmMessage.user("二")));

        // When / Then
        assertEquals(1, session.indexOfMessage(session.getMessages().get(1).getMessageId()));
        assertEquals(-1, session.indexOfMessage("ghost"));
        assertEquals(-1, session.indexOfMessage(null));
    }

    @Test
    void recordUsage_should_accumulate_without_adding_message() {
        // Given：上下文压缩这类「不产生消息的调用」只该涨用量
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When
        session.recordUsage(new LlmUsage(10, 5, 15));

        // Then
        assertEquals(0, session.size());
        assertEquals(15L, session.getUsage().getTotalTokens());
        assertEquals(1L, session.getUsage().getLlmCalls());
    }

    @Test
    void setCompaction_should_replace_and_allow_clearing() {
        // Given
        Session session = new Session("session-1", null, null, null, null, CREATED_AT);

        // When / Then
        assertNull(session.getCompaction());
        session.setCompaction(new SessionCompaction("摘要", "m-1", 5L, 0));
        assertEquals("摘要", session.getCompaction().getSummary());
        session.setCompaction(null);
        assertNull(session.getCompaction());
    }

    @Test
    void compaction_should_reject_blank_fields() {
        assertThrows(JellyfishException.class, () -> new SessionCompaction(" ", "m-1", 0L, 0));
        assertThrows(JellyfishException.class, () -> new SessionCompaction("摘要", null, 0L, 0));
    }

    @Test
    void setPermissionMode_should_fall_back_to_normal_when_null() {
        // Given
        Session session = new Session("session-1", null, null, null, PermissionMode.PLAN, CREATED_AT);

        // When
        session.setPermissionMode(null);

        // Then
        assertEquals(PermissionMode.NORMAL, session.getPermissionMode());
    }
}
