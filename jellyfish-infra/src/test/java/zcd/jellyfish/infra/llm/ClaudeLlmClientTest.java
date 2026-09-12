package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.RecordingListener;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.StubInterceptor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.errorStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.json;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.requestBody;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.sseStub;

/**
 * {@link ClaudeLlmClient} 的单元测试：重点覆盖工具结果合并、原生字段映射与流式事件。
 *
 * @author zcd
 */
class ClaudeLlmClientTest {

    /** 基准地址。 */
    private static final String BASE_URL = "https://api.anthropic.com";

    /** 覆盖文本、思考过程与工具调用三种 content block 的流式响应。 */
    private static final String STREAM_SSE =
            "event: message_start\n"
                    + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n"
                    + "event: content_block_start\n"
                    + "data: {\"type\":\"content_block_start\",\"index\":0,"
                    + "\"content_block\":{\"type\":\"text\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"why\"}}\n\n"
                    + "event: content_block_stop\n"
                    + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    + "event: content_block_start\n"
                    + "data: {\"type\":\"content_block_start\",\"index\":1,"
                    + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"search\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                    + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"abc\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                    + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"def\"}}\n\n"
                    + "event: content_block_stop\n"
                    + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                    + "event: message_delta\n"
                    + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                    + "\"usage\":{\"output_tokens\":5}}\n\n"
                    + "event: message_stop\n"
                    + "data: {\"type\":\"message_stop\"}\n\n";

    @Test
    void chat_should_merge_consecutive_tool_results_into_single_user_message() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.assistant(null, Arrays.asList(
                        new LlmToolCall(0, "t1", "toolA", "{}"),
                        new LlmToolCall(1, "t2", "toolB", "{}"))))
                .message(LlmMessage.tool("t1", "toolA", "r1"))
                .message(LlmMessage.tool("t2", "toolB", "r2"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode messages = json(requestBody(stub.lastRequest())).path("messages");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals(2, messages.get(1).path("content").size());
        assertEquals("user", messages.get(2).path("role").asText());
        assertEquals(2, messages.get(2).path("content").size());
        assertEquals("tool_result", messages.get(2).path("content").get(0).path("type").asText());
        assertEquals("t2", messages.get(2).path("content").get(1).path("tool_use_id").asText());
    }

    @Test
    void chat_should_send_anthropic_fields_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .temperature(0.5)
                .topP(0.9)
                .maxTokens(100)
                .stop(Arrays.asList("stop"))
                .tools(Collections.singletonList(
                        new LlmTool("search", "desc", Collections.singletonMap("q", "string"),
                                Collections.singletonList("q"))))
                .toolChoice("search")
                .build();

        // When
        client.chat(request);

        // Then
        Request httpRequest = stub.lastRequest();
        assertTrue(httpRequest.url().toString().endsWith("/v1/messages"));
        assertEquals("key", httpRequest.header("x-api-key"));
        assertEquals("2023-06-01", httpRequest.header("anthropic-version"));
        JsonNode body = json(requestBody(httpRequest));
        assertEquals("sys", body.path("system").asText());
        assertEquals(100, body.path("max_tokens").asInt());
        assertEquals(0.5, body.path("temperature").asDouble());
        assertEquals(0.9, body.path("top_p").asDouble());
        assertEquals("stop", body.path("stop_sequences").get(0).asText());
        assertEquals("search", body.path("tools").get(0).path("name").asText());
        assertTrue(body.path("tools").get(0).has("input_schema"));
        assertEquals("tool", body.path("tool_choice").path("type").asText());
        assertEquals("search", body.path("tool_choice").path("name").asText());
    }

    @Test
    void chat_should_default_max_tokens_when_not_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build());

        // Then
        assertEquals(4096, json(requestBody(stub.lastRequest())).path("max_tokens").asInt());
    }

    @Test
    void chat_should_collect_system_messages_into_system_field() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("prompt")
                .message(LlmMessage.system("extra"))
                .message(LlmMessage.user("hi"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("prompt\nextra", body.path("system").asText());
        assertEquals(1, body.path("messages").size());
    }

    @Test
    void chat_should_map_required_tool_choice_to_any_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(toolChoiceRequest("required"));

        // Then
        assertEquals("any", json(requestBody(stub.lastRequest()))
                .path("tool_choice").path("type").asText());
    }

    @Test
    void chat_should_omit_tool_choice_when_none() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(toolChoiceRequest("none"));

        // Then
        assertFalse(json(requestBody(stub.lastRequest())).has("tool_choice"));
    }

    @Test
    void chat_should_parse_text_thinking_and_tool_use_when_response_has_them() {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"why\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{\"a\":1}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":3,\"output_tokens\":4}}");
        ClaudeLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi")).build());

        // Then
        assertEquals("hi", response.getContent());
        assertEquals("why", response.getThinking());
        assertEquals("tool_use", response.getFinishReason());
        assertEquals(7, response.getUsage().getTotalTokens());
        assertEquals("search", response.getToolCalls().get(0).getName());
        assertEquals("{\"a\":1}", response.getToolCalls().get(0).getArguments());
    }

    @Test
    void chat_should_throw_when_response_content_is_not_array() {
        // Given
        ClaudeLlmClient client = client(jsonStub("{\"stop_reason\":\"end_turn\"}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(
                LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build()));
    }

    @Test
    void chat_should_throw_when_request_is_null() {
        // Given
        ClaudeLlmClient client = client(jsonStub("{\"content\":[]}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(null));
    }

    @Test
    void chatStream_should_emit_text_thinking_and_tool_call_when_stream_completes() {
        // Given
        ClaudeLlmClient client = client(sseStub(STREAM_SSE));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertEquals(Arrays.asList("Hi"), listener.texts);
        assertEquals(Arrays.asList("why"), listener.thinkings);
        assertEquals(1, listener.toolCalls.size());
        assertEquals("search", listener.toolCalls.get(0).getName());
        assertEquals("abcdef", listener.toolCalls.get(0).getArguments());
        assertEquals("Hi", listener.completed.getContent());
        assertEquals("why", listener.completed.getThinking());
        assertEquals("tool_use", listener.completed.getFinishReason());
        assertEquals(15, listener.completed.getUsage().getTotalTokens());
        assertNull(listener.error);
    }

    @Test
    void chatStream_should_notify_error_when_error_event_received() {
        // Given
        ClaudeLlmClient client = client(sseStub(
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}\n\n"));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
        assertTrue(listener.error.getMessage().contains("rate limited"));
        assertNull(listener.completed);
    }

    @Test
    void chatStream_should_notify_error_when_http_status_is_error() {
        // Given
        ClaudeLlmClient client = client(errorStub(429, "{\"error\":\"too many\"}"));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
    }

    /**
     * 构造带工具与指定 tool_choice 的请求。
     *
     * @param toolChoice 工具选择策略
     * @return 统一请求模型
     */
    private static LlmRequest toolChoiceRequest(String toolChoice) {
        return LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .tools(Collections.singletonList(new LlmTool("search", "desc", null, null)))
                .toolChoice(toolChoice)
                .build();
    }

    /**
     * 构造指向离线拦截器的 Claude 客户端。
     *
     * @param stub 拦截器桩
     * @return Claude 客户端
     */
    private static ClaudeLlmClient client(StubInterceptor stub) {
        return new ClaudeLlmClient(provider("claude", "key", BASE_URL), stub.client(), directExecutor());
    }
}
