package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.RecordingListener;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.StubInterceptor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.json;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.requestBody;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.sseStub;

/**
 * {@link GeminiLlmClient} 的单元测试：覆盖请求构建、工具名回填、响应解析与流式回调。
 *
 * @author zcd
 */
class GeminiLlmClientTest {

    /** 基准地址。 */
    private static final String BASE_URL = "https://generativelanguage.googleapis.com";

    /** 覆盖文本、思考过程与 functionCall 的流式响应。 */
    private static final String STREAM_SSE =
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}\n\n"
                    + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"think\","
                    + "\"thought\":true}]}}]}\n\n"
                    + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":"
                    + "{\"name\":\"search\",\"args\":{\"a\":1}}}]},\"finishReason\":\"STOP\"}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2,"
                    + "\"totalTokenCount\":3}}\n\n";

    @Test
    void chat_should_send_gemini_fields_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
        GeminiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gemini-1.5-pro")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .temperature(0.5)
                .topP(0.9)
                .maxTokens(128)
                .stop(Arrays.asList("stop"))
                .tools(Collections.singletonList(
                        new LlmTool("search", "desc", Collections.singletonMap("q", "string"),
                                Collections.singletonList("q"))))
                .toolChoice("required")
                .build();

        // When
        client.chat(request);

        // Then
        Request httpRequest = stub.lastRequest();
        assertTrue(httpRequest.url().toString()
                .endsWith("/v1beta/models/gemini-1.5-pro:generateContent"));
        assertEquals("key", httpRequest.header("x-goog-api-key"));
        JsonNode body = json(requestBody(httpRequest));
        assertEquals("hi", body.path("contents").get(0).path("parts").get(0).path("text").asText());
        assertEquals("user", body.path("contents").get(0).path("role").asText());
        assertEquals("sys", body.path("systemInstruction").path("parts").get(0).path("text").asText());
        assertEquals(0.5, body.path("generationConfig").path("temperature").asDouble());
        assertEquals(0.9, body.path("generationConfig").path("topP").asDouble());
        assertEquals(128, body.path("generationConfig").path("maxOutputTokens").asInt());
        assertEquals("stop", body.path("generationConfig").path("stopSequences").get(0).asText());
        assertEquals("search", body.path("tools").get(0).path("functionDeclarations").get(0)
                .path("name").asText());
        assertEquals("ANY", body.path("toolConfig").path("functionCallingConfig").path("mode").asText());
    }

    @Test
    void chat_should_strip_models_prefix_when_model_is_prefixed() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{}}]}");
        GeminiLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("models/gemini-1.5-pro").message(LlmMessage.user("hi")).build());

        // Then
        assertTrue(stub.lastRequest().url().toString()
                .contains("/models/gemini-1.5-pro:generateContent"));
    }

    @Test
    void chat_should_fill_function_response_name_from_history_when_tool_message_has_no_name()
            throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{}}]}");
        GeminiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gemini-1.5-pro")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.assistant(null, Collections.singletonList(
                        new LlmToolCall(0, "call-1", "toolX", "{}"))))
                .message(LlmMessage.tool("call-1", null, "result"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode contents = json(requestBody(stub.lastRequest())).path("contents");
        JsonNode functionResponse = contents.get(2).path("parts").get(0).path("functionResponse");
        assertEquals("toolX", functionResponse.path("name").asText());
    }

    @Test
    void chat_should_throw_when_function_response_name_cannot_be_resolved() {
        // Given
        GeminiLlmClient client = client(jsonStub("{\"candidates\":[{\"content\":{}}]}"));
        LlmRequest request = LlmRequest.builder("gemini-1.5-pro")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.tool("unknown", null, "result"))
                .build();

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(request));
    }

    @Test
    void chat_should_skip_assistant_message_when_content_and_tool_calls_are_empty() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{}}]}");
        GeminiLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("gemini-1.5-pro")
                .message(LlmMessage.user("a"))
                .message(LlmMessage.assistant(""))
                .message(LlmMessage.user("b"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode contents = json(requestBody(stub.lastRequest())).path("contents");
        assertEquals(2, contents.size());
        assertEquals("a", contents.get(0).path("parts").get(0).path("text").asText());
        assertEquals("b", contents.get(1).path("parts").get(0).path("text").asText());
    }

    @Test
    void chat_should_parse_text_thinking_and_function_call_when_response_has_parts() {
        // Given
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"},"
                + "{\"text\":\"think\",\"thought\":true},{\"functionCall\":{\"name\":\"f\","
                + "\"args\":{\"a\":1}}}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2,"
                + "\"totalTokenCount\":3}}");
        GeminiLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("gemini-1.5-pro")
                .message(LlmMessage.user("hi")).build());

        // Then
        assertEquals("hi", response.getContent());
        assertEquals("think", response.getThinking());
        assertEquals("STOP", response.getFinishReason());
        assertEquals(3, response.getUsage().getTotalTokens());
        assertEquals("f", response.getToolCalls().get(0).getName());
        assertEquals("{\"a\":1}", response.getToolCalls().get(0).getArguments());
        assertTrue(response.getToolCalls().get(0).getId().startsWith("gemini_call_"));
    }

    @Test
    void chat_should_throw_when_response_has_no_candidates() {
        // Given
        GeminiLlmClient client = client(jsonStub("{\"candidates\":[]}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(
                LlmRequest.builder("gemini-1.5-pro").message(LlmMessage.user("hi")).build()));
    }

    @Test
    void chat_should_throw_when_request_is_null() {
        // Given
        GeminiLlmClient client = client(jsonStub("{}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(null));
    }

    @Test
    void listModels_should_strip_models_prefix_when_response_is_valid() {
        // Given
        GeminiLlmClient client = client(jsonStub(
                "{\"models\":[{\"name\":\"models/gemini-1.5-pro\"},{\"name\":\"other\"}]}"));

        // When / Then
        assertEquals(Arrays.asList("gemini-1.5-pro", "other"), client.listModels());
    }

    @Test
    void listModels_should_return_empty_list_when_models_node_is_missing() {
        // Given
        GeminiLlmClient client = client(jsonStub("{}"));

        // When / Then
        assertTrue(client.listModels().isEmpty());
    }

    @Test
    void embedding_should_return_vector_when_response_is_valid() {
        // Given
        GeminiLlmClient client = client(jsonStub("{\"embedding\":{\"values\":[0.1,0.2]}}"));

        // When
        double[] vector = client.embedding("text");

        // Then
        assertEquals(2, vector.length);
        assertEquals(0.1, vector[0]);
        assertEquals(0.2, vector[1]);
    }

    @Test
    void embedding_should_throw_when_values_are_missing() {
        // Given
        GeminiLlmClient client = client(jsonStub("{\"embedding\":{}}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.embedding("text"));
    }

    @Test
    void chatStream_should_emit_text_thinking_and_function_call_when_stream_completes() {
        // Given
        GeminiLlmClient client = client(sseStub(STREAM_SSE));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("gemini-1.5-pro").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertEquals(Arrays.asList("Hi"), listener.texts);
        assertEquals(Arrays.asList("think"), listener.thinkings);
        assertEquals(1, listener.toolCalls.size());
        assertEquals("search", listener.toolCalls.get(0).getName());
        assertEquals("{\"a\":1}", listener.toolCalls.get(0).getArguments());
        assertEquals("STOP", listener.completed.getFinishReason());
        assertEquals(3, listener.completed.getUsage().getTotalTokens());
        assertNull(listener.error);
    }

    @Test
    void chat_should_parse_cached_content_tokens_when_reported() throws IOException {
        // Given：cachedContentTokenCount 是 promptTokenCount 的子集，不需要归一化
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1024,\"candidatesTokenCount\":2,"
                + "\"totalTokenCount\":1026,\"cachedContentTokenCount\":768}}");
        GeminiLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(
                LlmRequest.builder("gemini-pro").message(LlmMessage.user("hi")).build());

        // Then
        assertEquals(1024, response.getUsage().getPromptTokens());
        assertEquals(768, response.getUsage().getCacheReadTokens());
    }

    @Test
    void chat_should_deep_merge_extraBody_into_generationConfig_when_configured() throws IOException {
        // Given：用户在 generationConfig 里加思考预算，同时采样参数走内核字段
        StubInterceptor stub = jsonStub("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
        GeminiLlmClient client = client(stub);
        Map<String, Object> thinkingConfig = new LinkedHashMap<String, Object>();
        thinkingConfig.put("thinkingBudget", 2048);
        Map<String, Object> generationConfig = new LinkedHashMap<String, Object>();
        generationConfig.put("thinkingConfig", thinkingConfig);
        LlmRequest request = LlmRequest.builder("gemini-1.5-pro")
                .message(LlmMessage.user("hi"))
                .temperature(0.5)
                .maxTokens(128)
                .extraBody(Collections.<String, Object>singletonMap("generationConfig", generationConfig))
                .build();

        // When
        client.chat(request);

        // Then：浅合并会把内核生成的 temperature / maxOutputTokens 整块挤掉且不报错，
        // 因此这里必须深合并——两者都得在
        JsonNode config = json(requestBody(stub.lastRequest())).path("generationConfig");
        assertEquals(2048, config.path("thinkingConfig").path("thinkingBudget").asInt());
        assertEquals(0.5, config.path("temperature").asDouble());
        assertEquals(128, config.path("maxOutputTokens").asInt());
    }

    @Test
    void listModels_should_send_custom_headers_when_configured() {
        // Given：网关要求自定义鉴权头时，发现模型这一步会先于对话失败
        StubInterceptor stub = jsonStub("{\"models\":[{\"name\":\"models/gemini-1.5-pro\"}]}");
        Provider custom = new Provider("gemini", "gemini", "key", BASE_URL, Collections.emptyList(),
                null, null, null, Collections.singletonMap("x-tenant", "t-1"));
        GeminiLlmClient customClient = new GeminiLlmClient(custom, stub.client(), directExecutor());

        // When
        customClient.listModels();

        // Then
        assertEquals("t-1", stub.lastRequest().header("x-tenant"));
    }

    /**
     * 构造指向离线拦截器的 Gemini 客户端。
     *
     * @param stub 拦截器桩
     * @return Gemini 客户端
     */
    private static GeminiLlmClient client(StubInterceptor stub) {
        return new GeminiLlmClient(provider("gemini", "key", BASE_URL), stub.client(), directExecutor());
    }
}
