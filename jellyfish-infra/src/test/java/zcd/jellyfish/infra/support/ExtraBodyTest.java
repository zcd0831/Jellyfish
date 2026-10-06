package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtraBody} 的单元测试：保留键、深度上限、深合并与只读副本。
 *
 * @author zcd
 */
class ExtraBodyTest {

    @Test
    void sanitize_should_return_empty_map_when_raw_is_null() {
        // When / Then
        assertTrue(ExtraBody.sanitize(null, "provider[openai]").isEmpty());
    }

    @Test
    void sanitize_should_drop_reserved_keys_at_any_depth() {
        // Given：顶层与 generationConfig 里各塞一个保留键（Gemini 的采样参数就藏在这一层）
        Map<String, Object> generationConfig = mapOf("thinkingConfig", mapOf("thinkingBudget", 1024),
                "maxOutputTokens", 999);
        Map<String, Object> raw = mapOf("reasoning_effort", "low", "messages", "伪造的历史",
                "generationConfig", generationConfig);

        // When
        Map<String, Object> result = ExtraBody.sanitize(raw, "provider[openai]");

        // Then：两个保留键都被丢弃，其余原样保留——只看顶层等于给同一个参数留了后门
        assertFalse(result.containsKey("messages"));
        assertEquals("low", result.get("reasoning_effort"));
        Map<String, Object> cleaned = castMap(result.get("generationConfig"));
        assertFalse(cleaned.containsKey("maxOutputTokens"));
        assertTrue(cleaned.containsKey("thinkingConfig"));
    }

    @Test
    void sanitize_should_drop_subtree_beyond_max_depth() {
        // Given：构造一条正好超过深度上限的链路，末端放一个可辨认的键
        Map<String, Object> current = mapOf("tooDeep", "v");
        for (int i = 0; i < ExtraBody.MAX_DEPTH; i++) {
            current = mapOf("level" + i, current);
        }

        // When
        Map<String, Object> result = ExtraBody.sanitize(current, "provider[openai]");

        // Then：整棵超深子树被丢弃，而不是截断成半截参数
        Map<String, Object> probe = result;
        while (probe.size() == 1 && probe.values().iterator().next() instanceof Map) {
            probe = castMap(probe.values().iterator().next());
        }
        assertFalse(probe.containsKey("tooDeep"));
    }

    @Test
    void sanitize_should_drop_value_that_cannot_be_carried() {
        // Given：一个既不是标量也不是集合的值（JSON 里不会出现，但 LlmRequest 可以被直接构造）
        Map<String, Object> raw = mapOf("weird", new Object(), "ok", "v");

        // When
        Map<String, Object> result = ExtraBody.sanitize(raw, "provider[openai]");

        // Then
        assertFalse(result.containsKey("weird"));
        assertEquals("v", result.get("ok"));
    }

    @Test
    void sanitize_should_return_unmodifiable_copy() {
        // Given
        Map<String, Object> nested = mapOf("thinkingBudget", 1024);
        Map<String, Object> raw = mapOf("generationConfig", nested);

        // When
        Map<String, Object> result = ExtraBody.sanitize(raw, "provider[openai]");

        // Then：返回值与被清洗的原始对象都不能被读取方改动
        assertThrows(UnsupportedOperationException.class, () -> result.put("extra", "x"));
        assertThrows(UnsupportedOperationException.class,
                () -> castMap(result.get("generationConfig")).put("extra", "x"));
        assertTrue(nested.containsKey("thinkingBudget"));
    }

    @Test
    void merge_should_deepMerge_nested_maps_and_replace_arrays() {
        // Given：两侧都写 generationConfig（Gemini 的生成参数容器）
        Map<String, Object> base = mapOf("generationConfig", mapOf("temperature", 0.2d),
                "stopList", Arrays.asList("a", "b"), "service_tier", "flex");
        Map<String, Object> override = mapOf("generationConfig",
                mapOf("thinkingConfig", mapOf("thinkingBudget", 1024)),
                "stopList", Collections.singletonList("c"));

        // When
        Map<String, Object> merged = ExtraBody.merge(base, override);

        // Then：对象递归合并——浅合并会把内核生成的 temperature 整块挤掉，且不报错
        Map<String, Object> config = castMap(merged.get("generationConfig"));
        assertEquals(0.2d, config.get("temperature"));
        assertEquals(1024, castMap(config.get("thinkingConfig")).get("thinkingBudget"));
        // 数组整体替换：按位合并没有能自洽的规则
        assertEquals(Collections.singletonList("c"), merged.get("stopList"));
        assertEquals("flex", merged.get("service_tier"));
    }

    @Test
    void merge_should_return_other_side_when_one_side_is_empty() {
        // Given
        Map<String, Object> base = mapOf("service_tier", "flex");

        // When / Then：空的一侧不产生新对象，也不丢另一侧
        assertEquals(base, ExtraBody.merge(base, Collections.<String, Object>emptyMap()));
        assertEquals(base, ExtraBody.merge(Collections.<String, Object>emptyMap(), base));
        assertTrue(ExtraBody.merge(null, null).isEmpty());
    }

    @Test
    void applyTo_should_merge_into_body_and_report_applied_keys() {
        // Given
        Map<String, Object> body = mapOf("model", "gpt-4o", "stream", true);

        // When
        List<String> applied = ExtraBody.applyTo(body, mapOf("service_tier", "flex"));

        // Then
        assertEquals("flex", body.get("service_tier"));
        assertEquals(Collections.singletonList("service_tier"), applied);
    }

    @Test
    void applyTo_should_skip_reserved_keys_when_request_carries_them_directly() {
        // Given：正常路径上配置期已清干净，这里模拟被直接构造的请求
        Map<String, Object> body = mapOf("model", "gpt-4o", "messages", "内核拼好的历史");
        Map<String, Object> extra = mapOf("model", "另一个模型", "temperature", 0.5d, "service_tier", "flex");

        // When
        List<String> applied = ExtraBody.applyTo(body, extra);

        // Then：保留键一个都不落，其余照发
        assertEquals("gpt-4o", body.get("model"));
        assertEquals("内核拼好的历史", body.get("messages"));
        assertFalse(body.containsKey("temperature"));
        assertEquals(Collections.singletonList("service_tier"), applied);
    }

    @Test
    void applyTo_should_return_empty_list_when_nothing_to_apply() {
        // Given
        Map<String, Object> body = mapOf("model", "gpt-4o");

        // When / Then
        assertTrue(ExtraBody.applyTo(body, null).isEmpty());
        assertTrue(ExtraBody.applyTo(body, Collections.<String, Object>emptyMap()).isEmpty());
    }

    @Test
    void sanitize_should_drop_every_sampling_key_spelling() {
        // Given：三家对同一批参数各有拼法，少写一个就等于给同一个参数留后门
        Map<String, Object> raw = mapOf("temperature", 0.5d, "top_p", 0.9d, "topP", 0.9d,
                "stop", "x", "stop_sequences", "x", "stopSequences", "x",
                "max_tokens", 1, "maxOutputTokens", 1, "service_tier", "flex");

        // When
        Map<String, Object> result = ExtraBody.sanitize(raw, "provider[openai]");

        // Then：只剩那个真正私有的字段
        assertEquals(Collections.singletonMap("service_tier", "flex"), result);
    }

    @Test
    void isReserved_should_recognize_structural_and_sampling_keys() {
        // When / Then：大小写敏感——Gemini 的 topP 与 OpenAI 的 top_p 是两个键，都得挡
        assertTrue(ExtraBody.isReserved("messages"));
        assertTrue(ExtraBody.isReserved("top_p"));
        assertTrue(ExtraBody.isReserved("topP"));
        assertFalse(ExtraBody.isReserved("service_tier"));
        assertFalse(ExtraBody.isReserved(null));
    }

    /**
     * 构造一个有序映射。
     *
     * @param keyValues 交替出现的键与值
     * @return 映射
     */
    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 把「值确定是映射」的断言收在一处，避免测试里散落强制转换。
     *
     * @param value 待转换的值
     * @return 映射视图
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
