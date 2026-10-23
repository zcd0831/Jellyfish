package io.github.zcd0831.jellyfish.infrastructure.tokenizer;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 策略链对外只暴露一个数：正整数、且随内容增长。规则出处：技术决策 D08。 */
class CompositeTokenCounterTest {

    private final CompositeTokenCounter counter = new CompositeTokenCounter();

    @Test
    void 返回正数且随内容增长() {
        int shortText = counter.count("系统", Arrays.asList(ChatMessage.of(RecordRole.USER, "你好")),
                ModelId.of("gpt-4o"));
        int longText = counter.count("系统", Arrays.asList(ChatMessage.of(RecordRole.USER,
                "你好，请帮我读一下这个文件并总结要点，然后再解释每一段的作用。")), ModelId.of("gpt-4o"));

        assertTrue(shortText > 0);
        assertTrue(longText > shortText);
    }
}
