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
    }

    @Test
    void merge_should_take_override_field_by_field_and_keep_base_otherwise() {
        // Given：provider 级配齐三项，model 级只想改温度
        SamplingSettings base = new SamplingSettings(0.2d, 0.9d, Collections.singletonList("base"));
        SamplingSettings override = new SamplingSettings(1d, null, null);

        // When
        SamplingSettings merged = SamplingSettings.merge(base, override);

        // Then：只有表了态的字段被覆盖，其余沿用——否则用户要在每个 model 上抄一遍
        assertEquals(1d, merged.getTemperature());
        assertEquals(0.9d, merged.getTopP());
        assertEquals(Collections.singletonList("base"), merged.getStop());
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
    void deserialization_should_bind_all_three_fields() {
        // Given
        String json = "{\"temperature\":0.2,\"topP\":0.9,\"stop\":[\"</done>\"]}";

        // When
        SamplingSettings settings = ObjectMapperWrapper.readValue(json, SamplingSettings.class);

        // Then
        assertEquals(0.2d, settings.getTemperature());
        assertEquals(0.9d, settings.getTopP());
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
