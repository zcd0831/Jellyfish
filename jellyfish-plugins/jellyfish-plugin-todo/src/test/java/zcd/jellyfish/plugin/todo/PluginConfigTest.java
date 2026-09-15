package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PluginConfig} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办插件配置")
class PluginConfigTest {

    @Test
    @DisplayName("未配置时用默认目录，并把 ~ 展开为用户主目录")
    void from_should_useDefaultDirectory_when_notConfigured() {
        PluginConfig config = PluginConfig.from(null);
        PluginConfig empty = PluginConfig.from(Collections.<String, Object>emptyMap());

        Path expected = Paths.get(System.getProperty("user.home"), "jellyfish", "todos")
                .toAbsolutePath().normalize();
        assertEquals(expected, config.todoDirectory());
        assertEquals(expected, empty.todoDirectory());
    }

    @Test
    @DisplayName("自定义目录应生效并规范化为绝对路径")
    void from_should_useConfiguredDirectory() {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put(PluginConfig.KEY_TODO_DIR, "build/todos");

        PluginConfig config = PluginConfig.from(configuration);

        assertEquals(Paths.get("build/todos").toAbsolutePath().normalize(), config.todoDirectory());
    }

    @Test
    @DisplayName("目录值不是非空字符串应当场报错，而不是悄悄用默认值")
    void from_should_rejectInvalidDirectory() {
        Map<String, Object> blank = new HashMap<String, Object>();
        blank.put(PluginConfig.KEY_TODO_DIR, "   ");
        Map<String, Object> notString = new HashMap<String, Object>();
        notString.put(PluginConfig.KEY_TODO_DIR, 42);

        assertThrows(JellyfishException.class, () -> PluginConfig.from(blank));
        assertThrows(JellyfishException.class, () -> PluginConfig.from(notString));
    }
}
