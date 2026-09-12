package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.StubInterceptor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;

/**
 * {@link MiniMaxLlmClient} 的单元测试：验证复用 OpenAI 协议与默认 baseUrl 回退。
 *
 * @author zcd
 */
class MiniMaxLlmClientTest {

    @Test
    void chat_should_fall_back_to_default_base_url_when_provider_base_url_is_missing() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        MiniMaxLlmClient client = new MiniMaxLlmClient(
                provider("minimax", "key", null), stub.client(), directExecutor());

        // When
        client.chat(LlmRequest.builder("minimax-chat").message(LlmMessage.user("hi")).build());

        // Then
        assertTrue(stub.lastRequest().url().toString()
                .startsWith("https://api.minimax.io/v1/chat/completions"));
    }

    @Test
    void defaultBaseUrl_should_return_minimax_host() {
        // Given
        MiniMaxLlmClient client = new MiniMaxLlmClient(
                provider("minimax", "key", "https://proxy.example.com"), jsonStub("{}").client(),
                directExecutor());

        // Then
        assertTrue(client.defaultBaseUrl().contains("minimax"));
    }
}
