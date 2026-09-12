package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ConfigPaths} 的单元测试：验证默认值与非空字段的读写行为。
 *
 * @author zcd
 */
class ConfigPathsTest {

    @Test
    void getGlobalPath_should_return_empty_string_when_not_set() {
        // Given / When
        ConfigPaths paths = new ConfigPaths();

        // Then
        assertEquals("", paths.getGlobalPath());
    }

    @Test
    void getProjectPath_should_return_empty_string_when_not_set() {
        // Given / When
        ConfigPaths paths = new ConfigPaths();

        // Then
        assertEquals("", paths.getProjectPath());
    }

    @Test
    void getGlobalPath_should_return_set_value() {
        // Given
        ConfigPaths paths = new ConfigPaths();

        // When
        paths.setGlobalPath("/etc/jellyfish/jellyfish.json");

        // Then
        assertEquals("/etc/jellyfish/jellyfish.json", paths.getGlobalPath());
    }

    @Test
    void getProjectPath_should_return_set_value() {
        // Given
        ConfigPaths paths = new ConfigPaths();

        // When
        paths.setProjectPath("./jellyfish.json");

        // Then
        assertEquals("./jellyfish.json", paths.getProjectPath());
    }
}
