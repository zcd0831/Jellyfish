package io.github.zcd0831.jellyfish.infrastructure.tokenizer;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 估算必须偏大：CJK 按 1 token/字，其它按 4 字符/token，每条消息 +4。
 */
class EstimatorStrategyTest {

    private final EstimatorStrategy strategy = new EstimatorStrategy();

    @Test
    void 中文按字计() {
        int tokens = strategy.count(null, Arrays.asList(ChatMessage.of(RecordRole.USER, "你好世界")));

        assertTrue(tokens >= 4, "中文四字至少 4 token，实际 " + tokens);
    }

    @Test
    void 英文按四字符计() {
        int tokens = strategy.count(null, Arrays.asList(ChatMessage.of(RecordRole.USER, "abcdefgh")));

        assertTrue(tokens >= 2 && tokens <= 8, "8 个 ASCII 字符应在 2~8 token 之间，实际 " + tokens);
    }

    @Test
    void 系统提示词单独计入() {
        int without = strategy.count(null, Collections.<ChatMessage>emptyList());
        int with = strategy.count("你是助手", Collections.<ChatMessage>emptyList());

        assertTrue(with > without);
    }
}
