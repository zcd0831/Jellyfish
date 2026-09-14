package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextWindow} 的单元测试：验证成组保留、最新组不丢与预算耗尽语义。
 *
 * @author zcd
 */
class ContextWindowTest {

    @Test
    void crop_should_return_empty_for_blank_input() {
        // When / Then
        assertTrue(ContextWindow.crop(null, 100).getMessages().isEmpty());
        assertTrue(ContextWindow.crop(Collections.<LlmMessage>emptyList(), 100).getMessages().isEmpty());
        assertFalse(ContextWindow.crop(Collections.<LlmMessage>emptyList(), 100).isTruncated());
    }

    @Test
    void crop_should_keep_all_when_budget_is_enough() {
        // Given
        List<LlmMessage> messages = Arrays.asList(LlmMessage.user("hi"), LlmMessage.assistant("ok"));

        // When
        ContextWindow.Result result = ContextWindow.crop(messages, 1000);

        // Then
        assertEquals(2, result.getMessages().size());
        assertFalse(result.isTruncated());
    }

    @Test
    void crop_should_drop_oldest_group_when_budget_tight() {
        // Given：user（开销 4 + 1）与 assistant+tool 两组
        LlmMessage user = LlmMessage.user("u1");
        LlmMessage assistant = LlmMessage.assistant(null,
                Collections.singletonList(new LlmToolCall(0, "call_1", "read", "{}")));
        LlmMessage tool = LlmMessage.tool("call_1", "read", "result");
        List<LlmMessage> messages = Arrays.asList(user, assistant, tool);

        // When：预算只够 assistant+tool（15）而装不下 user（5）
        ContextWindow.Result result = ContextWindow.crop(messages, 16);

        // Then：旧组被整组丢弃，且工具结果没有与 assistant 拆散
        assertTrue(result.isTruncated());
        assertEquals(2, result.getMessages().size());
        assertTrue(result.getMessages().get(0).hasToolCalls());
        assertEquals(LlmMessage.ROLE_TOOL, result.getMessages().get(1).getRole());
    }

    @Test
    void crop_should_keep_only_newest_group_when_budget_exhausted() {
        // Given
        List<LlmMessage> messages = Arrays.asList(LlmMessage.user("u1"), LlmMessage.assistant("a2"));

        // When
        ContextWindow.Result result = ContextWindow.crop(messages, 0);

        // Then
        assertTrue(result.isTruncated());
        assertEquals(1, result.getMessages().size());
        assertEquals("a2", result.getMessages().get(0).getContent());
    }

    @Test
    void crop_should_truncate_oversized_newest_message() {
        // Given：一条超长用户消息，超出预算的部分应被截断
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append('a');
        }
        List<LlmMessage> messages = Collections.<LlmMessage>singletonList(LlmMessage.user(longText.toString()));

        // When
        ContextWindow.Result result = ContextWindow.crop(messages, 3);

        // Then
        assertEquals(1, result.getMessages().size());
        String content = result.getMessages().get(0).getContent();
        assertTrue(content.length() < longText.length());
        assertTrue(content.contains("截断"));
    }

    @Test
    void crop_should_not_split_tool_group_when_it_is_newest() {
        // Given：最新一组就是 assistant+tool
        List<LlmMessage> messages = new ArrayList<LlmMessage>();
        messages.add(LlmMessage.user("old"));
        messages.add(LlmMessage.assistant(null,
                Collections.singletonList(new LlmToolCall(0, "call_1", "read", "{}"))));
        messages.add(LlmMessage.tool("call_1", "read", "result"));

        // When
        ContextWindow.Result result = ContextWindow.crop(messages, 0);

        // Then
        assertEquals(2, result.getMessages().size());
        assertTrue(result.getMessages().get(0).hasToolCalls());
        assertEquals(LlmMessage.ROLE_TOOL, result.getMessages().get(1).getRole());
    }
}
