package zcd.jellyfish.plugin.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zcd.jellyfish.api.JellyfishException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PluginConfig} 的单元测试：两项都是「可缺省的正整数」。
 * <p>
 * 关键语义是<b>缺省 ≠ 0</b>：不配置要返回 {@code null}（不表态，内核用自己缺省值），
 * 而不是本插件编一个数字把内核的配置压掉。
 *
 * @author zcd
 */
@DisplayName("压缩插件配置")
class PluginConfigTest {

    @Test
    @DisplayName("配置段为空时两项都不表态")
    void from_should_returnNulls_when_configurationMissing() {
        PluginConfig config = PluginConfig.from(null);

        assertNull(config.keepRecentMessages());
        assertNull(config.maxSummaryChars());
    }

    @Test
    @DisplayName("配置段为空映射时同样不表态")
    void from_should_returnNulls_when_configurationEmpty() {
        PluginConfig config = PluginConfig.from(new HashMap<String, Object>());

        assertNull(config.keepRecentMessages());
        assertNull(config.maxSummaryChars());
    }

    @Test
    @DisplayName("数字与字符串两种写法都认：jellyfish.json 里两种都常见")
    void from_should_acceptNumberAndString() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_KEEP_RECENT_MESSAGES, 12);
        values.put(PluginConfig.KEY_MAX_SUMMARY_CHARS, " 1500 ");

        PluginConfig config = PluginConfig.from(values);

        assertEquals(12, config.keepRecentMessages());
        assertEquals(1500, config.maxSummaryChars());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100000"})
    @DisplayName("超出允许区间的保留条数直接抛：配置错就该在启动期被看见")
    void from_should_rejectOutOfRangeKeepRecent(String raw) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_KEEP_RECENT_MESSAGES, raw);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("不是数字的字符串直接抛")
    void from_should_rejectNonNumeric() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_MAX_SUMMARY_CHARS, "很多");

        assertThrows(JellyfishException.class, () -> PluginConfig.from(values));
    }

    @Test
    @DisplayName("类型不对（布尔、布尔串）直接抛")
    void from_should_rejectWrongType() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(PluginConfig.KEY_KEEP_RECENT_MESSAGES, Boolean.TRUE);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(values));
    }
}
