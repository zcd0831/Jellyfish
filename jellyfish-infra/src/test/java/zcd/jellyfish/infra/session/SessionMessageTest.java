package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionMessage} 的单元测试：验证工厂生成、字段透传、角色投影与不可变性。
 *
 * @author zcd
 */
class SessionMessageTest {

    @Test
    void of_should_generate_unique_id_and_current_timestamp() {
        // Given
        long before = System.currentTimeMillis();

        // When
        SessionMessage first = SessionMessage.of(LlmMessage.user("hi"));
        SessionMessage second = SessionMessage.of(LlmMessage.user("hi"));
        long after = System.currentTimeMillis();

        // Then
        assertNotNull(first.getMessageId());
        assertNotEquals(first.getMessageId(), second.getMessageId());
        assertTrue(first.getTimestamp() >= before && first.getTimestamp() <= after);
    }

    @Test
    void of_should_keep_usage_null_when_not_provided() {
        // When
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("ok"));

        // Then
        assertNull(message.getUsage());
    }

    @Test
    void of_should_keep_provided_usage() {
        // Given
        LlmUsage usage = new LlmUsage(1, 2, 3);

        // When
        SessionMessage message = SessionMessage.of(LlmMessage.assistant("ok"), usage);

        // Then
        assertSame(usage, message.getUsage());
    }

    @Test
    void getRole_should_project_from_llm_message() {
        // When
        SessionMessage message = SessionMessage.of(LlmMessage.tool("call-1", "read_file", "content"));

        // Then
        assertEquals(LlmMessage.ROLE_TOOL, message.getRole());
    }

    @Test
    void constructor_should_throw_when_message_id_blank() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> new SessionMessage(" ", 1L, LlmMessage.user("hi"), null));
    }

    @Test
    void constructor_should_throw_when_message_id_null() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> new SessionMessage(null, 1L, LlmMessage.user("hi"), null));
    }

    @Test
    void constructor_should_throw_when_message_null() {
        // When / Then
        assertThrows(NullPointerException.class,
                () -> new SessionMessage("message-1", 1L, null, null));
    }

    @Test
    void getMessage_should_return_exact_same_instance() {
        // Given
        LlmMessage llmMessage = LlmMessage.assistant("ok");

        // When
        SessionMessage message = SessionMessage.of(llmMessage);

        // Then
        assertSame(llmMessage, message.getMessage());
    }
}
