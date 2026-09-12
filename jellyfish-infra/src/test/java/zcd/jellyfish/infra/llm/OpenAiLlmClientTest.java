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
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.failureStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.json;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.requestBody;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.sseStub;

/**
 * {@link OpenAiLlmClient} 的单元测试：通过离线拦截器覆盖请求构建、响应解析与流式回调。
 *
 * @author zcd
 */
class OpenAiLlmClientTest {

    /** 基准地址。 */
    private static final String BASE_URL = "https://api.openai.com";

    /** 带文本增量的流式响应。 */
    private static final String TEXT_SSE =
            "data: {\"choices\":[{\"delta\":{\"content\":\"He\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,"
                    + "\"total_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n";

    /** 分片下发工具调用的流式响应。 */
    private static final String TOOL_SSE =
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                    + "\"function\":{\"name\":\"search\",\"arguments\":\"1\"}}]}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"2\"}}]}}]}\n\n"
                    + "data: [DONE]\n\n";

    @Test
    void constructor_should_throw_when_provider_is_null() {
        assertThrows(JellyfishException.class,
                () -> new OpenAiLlmClient(null, jsonStub("{}").client(), directExecutor()));
    }

    @Test
    void constructor_should_throw_when_api_key_is_blank() {
        assertThrows(JellyfishException.class,
                () -> new OpenAiLlmClient(provider("openai", "  ", BASE_URL), jsonStub("{}").client(),
                        directExecutor()));
    }

    @Test
    void constructor_should_throw_when_http_client_is_null() {
        assertThrows(JellyfishException.class,
                () -> new OpenAiLlmClient(provider("openai", "key", BASE_URL), null, directExecutor()));
    }

    @Test
    void constructor_should_throw_when_executor_is_null() {
        assertThrows(JellyfishException.class,
                () -> new OpenAiLlmClient(provider("openai", "key", BASE_URL), jsonStub("{}").client(), null));
    }

    @Test
    void chat_should_send_bearer_token_and_parse_response_when_response_is_valid() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"hi\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":2,\"total_tokens\":3}}");
        OpenAiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gpt-4o")
                .systemPrompt("sys")
                .message(LlmMessage.user("hello"))
                .build();

        // When
        LlmResponse response = client.chat(request);

        // Then
        assertEquals("hi", response.getContent());
        assertEquals("stop", response.getFinishReason());
        assertEquals(1, response.getUsage().getPromptTokens());
        Request httpRequest = stub.lastRequest();
        assertTrue(httpRequest.url().toString().endsWith("/v1/chat/completions"));
        assertEquals("Bearer key", httpRequest.header("Authorization"));
        JsonNode body = json(requestBody(httpRequest));
        assertEquals("gpt-4o", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean());
        assertFalse(body.has("stream_options"));
        assertEquals("sys", body.path("messages").get(0).path("content").asText());
        assertEquals("hello", body.path("messages").get(1).path("content").asText());
    }

    @Test
    void chat_should_send_sampling_params_and_tools_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("hello"))
                .temperature(0.5)
                .topP(0.9)
                .maxTokens(128)
                .stop(Arrays.asList("stop"))
                .tools(Collections.singletonList(
                        new LlmTool("search", "desc", Collections.singletonMap("q", "string"),
                                Collections.singletonList("q"))))
                .toolChoice("  auto  ")
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals(0.5, body.path("temperature").asDouble());
        assertEquals(0.9, body.path("top_p").asDouble());
        assertEquals(128, body.path("max_tokens").asInt());
        assertEquals("stop", body.path("stop").get(0).asText());
        assertEquals("auto", body.path("tool_choice").asText());
        JsonNode function = body.path("tools").get(0).path("function");
        assertEquals("search", function.path("name").asText());
        assertEquals("object", function.path("parameters").path("type").asText());
        assertEquals("q", function.path("parameters").path("required").get(0).asText());
    }

    @Test
    void chat_should_parse_tool_calls_when_response_contains_them() {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        OpenAiLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build());

        // Then
        assertTrue(response.hasToolCalls());
        assertEquals("call-1", response.getToolCalls().get(0).getId());
        assertEquals("search", response.getToolCalls().get(0).getName());
    }

    @Test
    void chat_should_throw_when_response_has_no_choices() {
        // Given
        OpenAiLlmClient client = client(jsonStub("{\"choices\":[]}"));

        // When / Then
        assertThrows(JellyfishException.class,
                () -> client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build()));
    }

    @Test
    void chat_should_throw_when_http_status_is_error() {
        // Given
        OpenAiLlmClient client = client(errorStub(400, "{\"error\":\"bad request\"}"));

        // When / Then
        assertThrows(JellyfishException.class,
                () -> client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build()));
    }

    @Test
    void chat_should_throw_when_request_is_null() {
        OpenAiLlmClient client = client(jsonStub("{}"));

        assertThrows(JellyfishException.class, () -> client.chat(null));
    }

    @Test
    void chatStream_should_send_stream_options_and_emit_text_when_stream_completes() throws IOException {
        // Given
        StubInterceptor stub = sseStub(TEXT_SSE);
        OpenAiLlmClient client = client(stub);
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build(), listener);

        // Then
        assertEquals(1, listener.openCount);
        assertEquals(Arrays.asList("He", "llo"), listener.texts);
        assertEquals("Hello", listener.completed.getContent());
        assertEquals(3, listener.completed.getUsage().getTotalTokens());
        assertNull(listener.error);
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void chatStream_should_merge_tool_call_fragments_when_stream_delivers_them() {
        // Given
        OpenAiLlmClient client = client(sseStub(TOOL_SSE));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build(), listener);

        // Then
        assertEquals(2, listener.toolCalls.size());
        LlmToolCall completed = listener.completed.getToolCalls().get(0);
        assertEquals("call-1", completed.getId());
        assertEquals("search", completed.getName());
        assertEquals("12", completed.getArguments());
    }

    @Test
    void chatStream_should_notify_error_when_http_status_is_error() {
        // Given
        OpenAiLlmClient client = client(errorStub(500, "{\"error\":\"boom\"}"));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build(), listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
        assertNull(listener.completed);
    }

    @Test
    void chatStream_should_notify_error_when_network_fails() {
        // Given
        OpenAiLlmClient client = client(failureStub());
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build(), listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
    }

    @Test
    void chatStream_should_notify_error_when_listener_is_null() {
        // Given
        OpenAiLlmClient client = client(jsonStub("{}"));

        // When / Then
        assertThrows(JellyfishException.class,
                () -> client.chatStream(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build(), null));
    }

    @Test
    void embedding_should_return_vector_when_response_is_valid() {
        // Given
        OpenAiLlmClient client = client(jsonStub("{\"data\":[{\"embedding\":[0.5,0.6]}]}"));

        // When
        double[] vector = client.embedding("text");

        // Then
        assertEquals(2, vector.length);
        assertEquals(0.5, vector[0]);
        assertEquals(0.6, vector[1]);
    }

    @Test
    void embedding_should_throw_when_response_data_is_empty() {
        // Given
        OpenAiLlmClient client = client(jsonStub("{\"data\":[]}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.embedding("text"));
    }

    /**
     * 构造指向离线拦截器的 OpenAI 客户端。
     *
     * @param stub 拦截器桩
     * @return OpenAI 客户端
     */
    private static OpenAiLlmClient client(StubInterceptor stub) {
        return new OpenAiLlmClient(provider("openai", "key", BASE_URL), stub.client(), directExecutor());
    }
}
