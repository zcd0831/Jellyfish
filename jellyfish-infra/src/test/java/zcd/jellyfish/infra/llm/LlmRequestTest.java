package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmRequest} 及其 builder 的单元测试。
 *
 * @author zcd
 */
class LlmRequestTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void build_should_throw_when_model_is_blank(String model) {
        assertThrows(JellyfishException.class, () -> LlmRequest.builder(model).build());
    }

    @Test
    void build_should_default_optional_collections_to_empty_when_not_set() {
        // When
        LlmRequest request = LlmRequest.builder("gpt").build();

        // Then
        assertTrue(request.getStop().isEmpty());
        assertTrue(request.getTools().isEmpty());
        assertFalse(request.hasTools());
        assertTrue(request.getMessages().isEmpty());
        assertNull(request.getSystemPrompt());
    }

    @Test
    void message_should_ignore_null_when_adding() {
        // When
        LlmRequest request = LlmRequest.builder("gpt")
                .message(null)
                .message(LlmMessage.user("hi"))
                .build();

        // Then
        assertEquals(1, request.getMessages().size());
        assertEquals("hi", request.getMessages().get(0).getContent());
    }

    @Test
    void messages_should_append_all_and_ignore_null_when_adding_batch() {
        // When
        LlmRequest request = LlmRequest.builder("gpt")
                .messages(null)
                .messages(Arrays.asList(LlmMessage.user("a"), LlmMessage.assistant("b")))
                .build();

        // Then
        assertEquals(2, request.getMessages().size());
    }

    @Test
    void getters_should_return_all_configured_values() {
        // Given
        LlmTool tool = new LlmTool("tool", "desc", null, null);
        LlmRequest request = LlmRequest.builder("gpt")
                .systemPrompt("sys")
                .temperature(0.5)
                .topP(0.9)
                .maxTokens(128)
                .stop(Arrays.asList("stop1"))
                .tools(Arrays.asList(tool))
                .toolChoice("auto")
                .build();

        // Then
        assertEquals("gpt", request.getModel());
        assertEquals("sys", request.getSystemPrompt());
        assertEquals(0.5, request.getTemperature());
        assertEquals(0.9, request.getTopP());
        assertEquals(128, request.getMaxTokens());
        assertEquals(1, request.getStop().size());
        assertTrue(request.hasTools());
        assertEquals("auto", request.getToolChoice());
    }

    @Test
    void getStop_should_not_reflect_external_mutation_when_source_list_changes() {
        // Given
        List<String> stop = new ArrayList<>();
        stop.add("a");
        LlmRequest request = LlmRequest.builder("gpt").stop(stop).build();

        // When
        stop.add("b");

        // Then
        assertEquals(1, request.getStop().size());
    }

    @Test
    void getTools_should_not_reflect_external_mutation_when_source_list_changes() {
        // Given
        List<LlmTool> tools = new ArrayList<>();
        tools.add(new LlmTool("tool", "desc", null, null));
        LlmRequest request = LlmRequest.builder("gpt").tools(tools).build();

        // When
        tools.add(new LlmTool("tool2", "desc", null, null));

        // Then
        assertEquals(1, request.getTools().size());
    }
}
