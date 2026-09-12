package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmClient} 默认实现的单元测试。
 *
 * @author zcd
 */
class LlmClientTest {

    @Test
    void listModels_should_return_empty_list_by_default() {
        assertTrue(new StubLlmClient().listModels().isEmpty());
    }

    @Test
    void embedding_should_throw_when_implementation_does_not_support_it() {
        assertThrows(JellyfishException.class, () -> new StubLlmClient().embedding("text"));
    }

    /**
     * 只实现必选方法的桩客户端，用于验证接口默认行为。
     *
     * @author zcd
     */
    private static final class StubLlmClient implements LlmClient {

        @Override
        public Provider getProvider() {
            return null;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            return null;
        }

        @Override
        public LlmStreamHandle chatStream(LlmRequest request, LlmStreamListener listener) {
            return null;
        }
    }
}
