package zcd.jellyfish.infra.llm;

import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;

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
}
