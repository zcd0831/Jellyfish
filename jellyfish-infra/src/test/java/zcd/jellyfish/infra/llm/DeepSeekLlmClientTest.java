package zcd.jellyfish.infra.llm;

import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.StubInterceptor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;

/**
 * {@link DeepSeekLlmClient} 的单元测试：验证复用 OpenAI 协议与默认 baseUrl 回退。
 *
 * @author zcd
 */
class DeepSeekLlmClientTest {

    @Test
    void chat_should_fall_back_to_default_base_url_when_provider_base_url_is_missing() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                provider("deepseek", "key", null), stub.client(), directExecutor());

        // When
        client.chat(LlmRequest.builder("deepseek-chat").message(LlmMessage.user("hi")).build());

        // Then
        Request request = stub.lastRequest();
        assertTrue(request.url().toString().startsWith("https://api.deepseek.com/v1/chat/completions"));
        assertTrue(request.header("Authorization").startsWith("Bearer "));
    }

    @Test
    void defaultBaseUrl_should_return_deepseek_host() {
        // Given
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                provider("deepseek", "key", "https://proxy.example.com"), jsonStub("{}").client(),
                directExecutor());

        // Then
        assertTrue(client.defaultBaseUrl().contains("deepseek.com"));
    }

    @Test
    void chat_should_parse_cache_hit_tokens_when_provider_reports_them() throws IOException {
        // Given：DeepSeek 的 prompt_tokens 已经包含命中部分（官方文档原话：prompt_tokens
        // “equals prompt_cache_hit_tokens + prompt_cache_miss_tokens”），因此不需要归一化
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":7,\"total_tokens\":107,"
                + "\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}");
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                provider("deepseek", "key", null), stub.client(), directExecutor());

        // When
        LlmResponse response = client.chat(
                LlmRequest.builder("deepseek-chat").message(LlmMessage.user("hi")).build());

        // Then：总输入沿用厂商给的 prompt_tokens，命中数单独带出；命中率 = 80/100
        assertEquals(100, response.getUsage().getPromptTokens());
        assertEquals(80, response.getUsage().getCacheReadTokens());
        assertEquals(0.8d, response.getUsage().getCacheHitRate(), 1e-9);
    }
}
