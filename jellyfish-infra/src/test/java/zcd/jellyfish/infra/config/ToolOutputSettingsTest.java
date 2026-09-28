package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolOutputSettings} 的单元测试：验证缺省值、非法值回退与反序列化。
 *
 * @author zcd
 */
class ToolOutputSettingsTest {

    @Test
    void constructor_should_apply_defaults_when_all_missing() {
        // When
        ToolOutputSettings settings = new ToolOutputSettings(null, null, null, null, null);

        // Then
        assertEquals(ToolOutputSettings.DEFAULT_DIR, settings.getDir());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_FILES, settings.getKeepFiles());
        assertEquals(ToolOutputSettings.DEFAULT_MAX_BYTES, settings.getMaxBytes());
        assertEquals(ToolOutputSettings.DEFAULT_SPILL_MAX_BYTES, settings.getSpillMaxBytes());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_RECENT_MESSAGES, settings.getKeepRecentMessages());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_values_invalid() {
        // When：目录空白、计数与字节为负，全部回退缺省
        ToolOutputSettings settings = new ToolOutputSettings("  ", -1, -1L, -1L, -1);

        // Then
        assertEquals(ToolOutputSettings.DEFAULT_DIR, settings.getDir());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_FILES, settings.getKeepFiles());
        assertEquals(ToolOutputSettings.DEFAULT_MAX_BYTES, settings.getMaxBytes());
        assertEquals(ToolOutputSettings.DEFAULT_SPILL_MAX_BYTES, settings.getSpillMaxBytes());
        assertEquals(ToolOutputSettings.DEFAULT_KEEP_RECENT_MESSAGES, settings.getKeepRecentMessages());
    }

    @Test
    void constructor_should_keep_explicit_zero_as_disabled() {
        // When：0 表示关闭对应治理项，是合法取值而非「未配置」
        ToolOutputSettings settings = new ToolOutputSettings(null, 0, 0L, 0L, 0);

        // Then
        assertEquals(0, settings.getKeepFiles());
        assertEquals(0L, settings.getMaxBytes());
        assertEquals(0L, settings.getSpillMaxBytes());
        assertEquals(0, settings.getKeepRecentMessages());
        assertFalse(settings.isDefault());
    }

    @Test
    void deserialization_should_bind_all_fields() {
        // Given
        String json = "{\"dir\":\"/tmp/a\",\"keepFiles\":1,\"maxBytes\":2,\"spillMaxBytes\":4,"
                + "\"keepRecentMessages\":3}";

        // When
        ToolOutputSettings settings = ObjectMapperWrapper.readValue(json, ToolOutputSettings.class);

        // Then
        assertEquals("/tmp/a", settings.getDir());
        assertEquals(1, settings.getKeepFiles());
        assertEquals(2L, settings.getMaxBytes());
        assertEquals(4L, settings.getSpillMaxBytes());
        assertEquals(3, settings.getKeepRecentMessages());
    }
}
