package io.github.zcd0831.jellyfish.application.shared;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 极简 JSON 解析的分支：对象/数组/字符串/数字/布尔/null 与各类非法输入。 */
class JsonTest {

    @Test
    void 空串返回空对象() {
        assertTrue(Json.parseObject(null).isEmpty());
        assertTrue(Json.parseObject("  ").isEmpty());
    }

    @Test
    void 解析各类型标量() {
        Map<String, Object> parsed = Json.parseObject(
                "{\"s\":\"文本\",\"i\":42,\"d\":1.5,\"b\":true,\"f\":false,\"n\":null}");

        assertEquals("文本", parsed.get("s"));
        assertEquals(42L, parsed.get("i"));
        assertEquals(1.5, parsed.get("d"));
        assertEquals(Boolean.TRUE, parsed.get("b"));
        assertEquals(Boolean.FALSE, parsed.get("f"));
        assertNull(parsed.get("n"));
    }

    @Test
    void 解析数组与嵌套对象() {
        Map<String, Object> parsed = Json.parseObject("{\"a\":[1,2],\"o\":{\"k\":\"v\"},\"e\":[],\"eo\":{}}");

        assertEquals(2, ((List<?>) parsed.get("a")).size());
        assertEquals("v", ((Map<?, ?>) parsed.get("o")).get("k"));
        assertTrue(((List<?>) parsed.get("e")).isEmpty());
        assertTrue(((Map<?, ?>) parsed.get("eo")).isEmpty());
    }

    @Test
    void 转义字符被还原() {
        Map<String, Object> parsed = Json.parseObject("{\"s\":\"a\\nb\\t\\\"c\\u0041\"}");

        assertEquals("a\nb\t\"cA", parsed.get("s"));
    }

    @Test
    void 非法结构抛异常() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\" 1}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":[1,}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("\"未闭合"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":[]}x"));
    }

    @Test
    void 非对象根返回空对象() {
        assertTrue(Json.parseObject("[1,2]").isEmpty());
        assertTrue(Json.parseObject("42").isEmpty());
    }
}
