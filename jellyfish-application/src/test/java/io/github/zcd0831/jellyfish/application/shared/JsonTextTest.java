package io.github.zcd0831.jellyfish.application.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JSON 字符串转义（不引 JSON 库）。 */
class JsonTextTest {

    @Test
    void 转义引号反斜杠与控制字符() {
        assertEquals("a\\\"b", JsonText.escape("a\"b"));
        assertEquals("a\\\\b", JsonText.escape("a\\b"));
        assertEquals("a\\nb", JsonText.escape("a\nb"));
        assertEquals("a\\rb", JsonText.escape("a\rb"));
        assertEquals("a\\tb", JsonText.escape("a\tb"));
    }

    @Test
    void 普通文本原样返回且null安全() {
        assertEquals("中文文本", JsonText.escape("中文文本"));
        assertEquals("", JsonText.escape(null));
    }
}
