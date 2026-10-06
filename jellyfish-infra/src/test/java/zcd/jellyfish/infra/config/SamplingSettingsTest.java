package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SamplingSettings} 的单元测试：缺省不下发、非法值丢弃、逐字段合并与反序列化。
 *
 * @author zcd
 */
class SamplingSettingsTest {

    @Test
    void constructor_should_leave_all_three_unset_by_default() {
        // When
        SamplingSettings settings = new SamplingSettings();

        // Then：三项都不表态，语义是「不下发」，而不是「用某个缺省值」
        assertNull(settings.getTemperature());
        assertNull(settings.getTopP());
        assertTrue(settings.getStop().isEmpty());
        assertTrue(settings.isEmpty());
    }

    @Test
    void constructor_should_drop_negative_or_non_finite_temperature() {
        // When / Then：非负与有限是内核能确定的边界，越界即丢弃
        assertNull(new SamplingSettings(-0.1d, null, null).getTemperature());
        assertNull(new SamplingSettings(Double.NaN, null, null).getTemperature());
        assertNull(new SamplingSettings(Double.POSITIVE_INFINITY, null, null).getTemperature());
        assertEquals(0d, new SamplingSettings(0d, null, null).getTemperature());
        assertEquals(2d, new SamplingSettings(2d, null, null).getTemperature());
    }

    @Test
    void constructor_should_drop_topP_out_of_range() {
        // When / Then：概率必须落在 (0, 1]，这一条各厂商一致
        assertNull(new SamplingSettings(null, 0d, null).getTopP());
        assertNull(new SamplingSettings(null, 1.5d, null).getTopP());
        assertEquals(1d, new SamplingSettings(null, 1d, null).getTopP());
    }

    @Test
    void constructor_should_drop_non_positive_topK() {
        // When / Then：0 与负数都不下发——Anthropic 只有大于 0 才启用，Gemini 的 0 含义是「不限制」，
        // 两家的「0」并不一致，因此内核不猜
        assertNull(new SamplingSettings(null, null, 0, null, null, null, null).getTopK());
        assertNull(new SamplingSettings(null, null, -5, null, null, null, null).getTopK());
        assertEquals(40, new SamplingSettings(null, null, 40, null, null, null, null).getTopK());
    }

    @Test
    void constructor_should_drop_penalty_out_of_range() {
        // When / Then：OpenAI 与 Gemini 都是 [-2, 2]
        assertNull(new SamplingSettings(null, null, null, null, -2.5d, null, null).getFrequencyPenalty());
        assertNull(new SamplingSettings(null, null, null, null, null, 3d, null).getPresencePenalty());
        assertEquals(-2d, new SamplingSettings(null, null, null, null, -2d, null, null).getFrequencyPenalty());
        assertEquals(2d, new SamplingSettings(null, null, null, null, null, 2d, null).getPresencePenalty());
    }

    @Test
    void constructor_should_keep_seed_only_when_present() {
        // When / Then：seed 没有能确定的取值区间，因此只区分「表了态」与「没表态」
        assertNull(new SamplingSettings(null, null, null, null, null, null, null).getSeed());
        assertEquals(-1L, new SamplingSettings(null, null, null, -1L, null, null, null).getSeed());
        assertEquals(42L, new SamplingSettings(null, null, null, 42L, null, null, null).getSeed());
    }

    @Test
    void constructor_should_leave_new_fields_unset_with_three_arg_constructor() {
        // When：旧的便捷构造器只表态三个基础参数
        SamplingSettings settings = new SamplingSettings(0.2d, 0.9d, Collections.singletonList("x"));

        // Then：新增的四个字段保持「不下发」
        assertNull(settings.getTopK());
        assertNull(settings.getSeed());
        assertNull(settings.getFrequencyPenalty());
        assertNull(settings.getPresencePenalty());
    }

    @Test
    void constructor_should_drop_blank_stop_entries_and_keep_values_verbatim() {
        // When：停止序列里的空格可能是它的一部分，不能替用户去空白
        SamplingSettings settings = new SamplingSettings(null, null,
                Arrays.asList("</done>", "  ", null, "\n\nHuman:"));

        // Then
        assertEquals(Arrays.asList("</done>", "\n\nHuman:"), settings.getStop());
    }

    @Test
    void isEmpty_should_be_false_when_any_field_is_set() {
        // When / Then
        assertFalse(new SamplingSettings(0.2d, null, null).isEmpty());
        assertFalse(new SamplingSettings(null, 0.9d, null).isEmpty());
        assertFalse(new SamplingSettings(null, null, Collections.singletonList("x")).isEmpty());
        assertFalse(new SamplingSettings(null, null, 40, null, null, null, null).isEmpty());
        assertFalse(new SamplingSettings(null, null, null, 7L, null, null, null).isEmpty());
        assertFalse(new SamplingSettings(null, null, null, null, 0.5d, null, null).isEmpty());
        assertFalse(new SamplingSettings(null, null, null, null, null, 0.5d, null).isEmpty());
    }

    @Test
    void merge_should_take_override_field_by_field_and_keep_base_otherwise() {
        // Given：provider 级配齐七项，model 级只想改温度
        SamplingSettings base = new SamplingSettings(0.2d, 0.9d, 40, 7L, 0.5d, 0.5d,
                Collections.singletonList("base"));
        SamplingSettings override = new SamplingSettings(1d, null, null, null, null, null, null);

        // When
        SamplingSettings merged = SamplingSettings.merge(base, override);

        // Then：只有表了态的字段被覆盖，其余沿用——否则用户要在每个 model 上抄一遍
        assertEquals(1d, merged.getTemperature());
        assertEquals(0.9d, merged.getTopP());
        assertEquals(40, merged.getTopK());
        assertEquals(7L, merged.getSeed());
        assertEquals(0.5d, merged.getFrequencyPenalty());
        assertEquals(0.5d, merged.getPresencePenalty());
        assertEquals(Collections.singletonList("base"), merged.getStop());
    }

    @Test
    void merge_should_override_each_new_field_independently() {
        // Given：只有 model 级的 seed 表了态
        SamplingSettings base = new SamplingSettings(0.2d, null, 40, 7L, null, null, null);
        SamplingSettings override = new SamplingSettings(null, null, null, 99L, null, null, null);

        // When
        SamplingSettings merged = SamplingSettings.merge(base, override);

        // Then
        assertEquals(99L, merged.getSeed());
        assertEquals(40, merged.getTopK());
    }

    @Test
    void merge_should_return_other_side_when_one_side_is_null() {
        // Given
        SamplingSettings base = new SamplingSettings(0.2d, null, null);

        // When / Then
        assertEquals(0.2d, SamplingSettings.merge(base, null).getTemperature());
        assertEquals(0.2d, SamplingSettings.merge(null, base).getTemperature());
        assertTrue(SamplingSettings.merge(null, null).isEmpty());
    }

    @Test
    void deserialization_should_bind_all_seven_fields() {
        // Given
        String json = "{\"temperature\":0.2,\"topP\":0.9,\"topK\":40,\"seed\":7,"
                + "\"frequencyPenalty\":0.5,\"presencePenalty\":-0.5,\"stop\":[\"</done>\"]}";

        // When
        SamplingSettings settings = ObjectMapperWrapper.readValue(json, SamplingSettings.class);

        // Then
        assertEquals(0.2d, settings.getTemperature());
        assertEquals(0.9d, settings.getTopP());
        assertEquals(40, settings.getTopK());
        assertEquals(7L, settings.getSeed());
        assertEquals(0.5d, settings.getFrequencyPenalty());
        assertEquals(-0.5d, settings.getPresencePenalty());
        assertEquals(Collections.singletonList("</done>"), settings.getStop());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // When
        SamplingSettings settings = ObjectMapperWrapper.readValue("{}", SamplingSettings.class);

        // Then
        assertTrue(settings.isEmpty());
    }
}
