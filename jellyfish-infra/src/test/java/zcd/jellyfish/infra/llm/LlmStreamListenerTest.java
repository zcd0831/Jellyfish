package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link LlmStreamListener} 默认实现的单元测试：确认未覆盖的方法都是空实现。
 *
 * @author zcd
 */
class LlmStreamListenerTest {

    @Test
    void default_methods_should_do_nothing_when_not_overridden() {
        // Given
        LlmStreamListener listener = new LlmStreamListener() {
        };

        // When / Then
        assertDoesNotThrow(() -> {
            listener.onOpen();
            listener.onText("text");
            listener.onThinking("thinking");
            listener.onToolCall(new LlmToolCall(0, "id", "name", "{}"));
            listener.onComplete(LlmResponse.text("done"));
            listener.onCancelled();
            listener.onError(new IllegalStateException("error"));
        });
    }
}
