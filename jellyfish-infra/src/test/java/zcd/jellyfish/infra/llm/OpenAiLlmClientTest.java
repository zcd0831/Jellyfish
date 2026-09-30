package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.LlmHttpException;

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
    void chat_should_send_prompt_cache_key_when_set() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("hello"))
                .cacheKey("s-1")
                .build();

        // When
        client.chat(request);

        // Then：让同一会话的请求尽量落到持有相同前缀的那台机器上
        assertEquals("s-1", json(requestBody(stub.lastRequest())).path("prompt_cache_key").asText());
    }

    @Test
    void chat_should_omit_prompt_cache_key_when_unset() throws IOException {
        // Given：缺省就是不下发（老模型/老端点收到不认识的字段可能直接报错）
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hello")).build());

        // Then
        assertFalse(json(requestBody(stub.lastRequest())).has("prompt_cache_key"));
    }

    @Test
    void chat_should_send_prompt_cache_retention_when_set() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("hello"))
                .cacheRetention("24h")
                .build();

        // When
        client.chat(request);

        // Then：取值由厂商约定，内核原样下发——它不替调用方猜，因为猜错就是一次 400
        assertEquals("24h", json(requestBody(stub.lastRequest())).path("prompt_cache_retention").asText());
    }

    @Test
    void chat_should_omit_prompt_cache_retention_when_unset() throws IOException {
        // Given：缺省不下发（各厂商取值与支持情况都不同）
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hello")).build());

        // Then
        assertFalse(json(requestBody(stub.lastRequest())).has("prompt_cache_retention"));
    }

    @Test
    void chat_should_sendOneMaxTokens_when_minimalOutputRequested() throws IOException {
        // Given：OpenAI 系的下限是 1——max_tokens: 0 会以 "'0' is less than the minimum of 1" 被拒，
        // 因此「不要求输出」在这里落成 1，而不是 Anthropic 那种 0
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).minimalOutput().build());

        // Then
        assertEquals(1, json(requestBody(stub.lastRequest())).path("max_tokens").asInt());
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
    void chat_should_carryStatusCode_when_http_status_is_error() {
        // Given：订阅方要靠状态码区分「字段被拒该降级」与「限流该重试」
        OpenAiLlmClient client = client(errorStub(400, "{\"error\":\"unknown field\"}"));

        // When
        LlmHttpException failure = assertThrows(LlmHttpException.class,
                () -> client.chat(LlmRequest.builder("gpt-4o").message(LlmMessage.user("hi")).build()));

        // Then：它仍然是 JellyfishException，既有捕获点一个都不用改
        assertEquals(400, failure.getStatusCode());
        assertTrue(failure.getMessage().contains("400"));
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

    @Test
    void chat_should_parse_cached_tokens_from_prompt_tokens_details() throws IOException {
        // Given：OpenAI 把命中数放在 prompt_tokens_details.cached_tokens，且 prompt_tokens 已含它
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"hi\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1024,\"completion_tokens\":8,\"total_tokens\":1032,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":896}}}");
        OpenAiLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("gpt-4o")
                .message(LlmMessage.user("hello")).build());

        // Then：总输入沿用 prompt_tokens，命中数是它的子集
        assertEquals(1024, response.getUsage().getPromptTokens());
        assertEquals(896, response.getUsage().getCacheReadTokens());
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
