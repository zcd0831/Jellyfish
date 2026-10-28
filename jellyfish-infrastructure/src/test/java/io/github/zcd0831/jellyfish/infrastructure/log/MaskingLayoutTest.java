package io.github.zcd0831.jellyfish.infrastructure.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 脱敏在适配器统一处理。。 */
class MaskingLayoutTest {

    @Test
    void 敏感字段的值被替换为星号() {
        String line = "ts=1 info key=sk-123 token=abc secret=x password=p authorization=Bearer%20k cookie=c";

        String masked = MaskingLayout.mask(line);

        assertEquals("ts=1 info key=*** token=*** secret=*** password=*** authorization=*** cookie=***",
                masked);
    }

    @Test
    void 普通字段不受影响() {
        String line = "ts=1 info model=gpt-4o retry=2 msg=安排重试";

        assertTrue(MaskingLayout.mask(line).contains("model=gpt-4o"));
        assertTrue(MaskingLayout.mask(line).contains("msg=安排重试"));
    }
}
