package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginConfig} 的单元测试。
 * <p>
 * 重点锁住默认目录与 {@code ~} 展开：内核只在配置文件的路径段上做展开，插件配置里的 {@code ~}
 * 会原样送到这里；不展开就会在工作目录下建一个名为 {@code ~} 的目录，而用户以为自己配的是主目录。
 *
 * @author zcd
 */
@DisplayName("会话文件插件配置解析")
class PluginConfigTest {

    /** 用户主目录，与实现保持同一口径。 */
    private static final String HOME = System.getProperty("user.home");

    @Test
    @DisplayName("没有配置段时应使用默认目录并启用 git")
    void from_should_useDefaults_when_configurationAbsent() {
        PluginConfig config = PluginConfig.from(null);

        assertEquals(Paths.get(HOME, "jellyfish", "sessions"), config.sessionDirectory());
        assertTrue(config.gitEnabled());
    }

    @Test
    @DisplayName("空配置段与没有配置段等价")
    void from_should_useDefaults_when_configurationEmpty() {
        assertEquals(PluginConfig.from(null).sessionDirectory(),
                PluginConfig.from(Collections.<String, Object>emptyMap()).sessionDirectory());
    }

    @Test
    @DisplayName("波浪号应展开为当前用户主目录")
    void from_should_expandTilde() {
        PluginConfig config = PluginConfig.from(config("sessionDir", "~/custom/sessions"));

        assertEquals(Paths.get(HOME, "custom", "sessions"), config.sessionDirectory());
    }

    @Test
    @DisplayName("相对目录应解析成绝对路径：落盘位置不能随进程工作目录漂移")
    void from_should_resolveRelativeDirectory() {
        PluginConfig config = PluginConfig.from(config("sessionDir", "session-store"));

        assertTrue(config.sessionDirectory().isAbsolute(), config.sessionDirectory().toString());
        assertEquals("session-store", config.sessionDirectory().getFileName().toString());
    }

    @Test
    @DisplayName("gitEnabled 兼容布尔与字符串两种写法")
    void from_should_acceptBooleanAndText() {
        assertFalse(PluginConfig.from(config("gitEnabled", false)).gitEnabled());
        assertFalse(PluginConfig.from(config("gitEnabled", "false")).gitEnabled());
        assertTrue(PluginConfig.from(config("gitEnabled", "TRUE")).gitEnabled());
    }

    @Test
    @DisplayName("sessionDir 类型不对应报错，而不是悄悄回退到默认目录")
    void from_should_fail_when_sessionDirNotString() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> PluginConfig.from(config("sessionDir", 42)));

        assertTrue(failure.getMessage().contains("sessionDir"), failure.getMessage());
    }

    @Test
    @DisplayName("gitEnabled 写错应报错，而不是当成 false 静默关掉版本历史")
    void from_should_fail_when_gitEnabledUnrecognized() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> PluginConfig.from(config("gitEnabled", "maybe")));

        assertTrue(failure.getMessage().contains("gitEnabled"), failure.getMessage());
    }

    /**
     * 构造单键配置段。
     *
     * @param key   键
     * @param value 值
     * @return 配置映射
     */
    private static Map<String, Object> config(String key, Object value) {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put(key, value);
        return configuration;
    }
}
