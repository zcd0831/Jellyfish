package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link AppConfig} 的单元测试：验证反序列化与缺省值。
 *
 * @author zcd
 */
class AppConfigTest {

    @Test
    void getModel_should_return_empty_paths_when_absent() {
        // Given
        AppConfig appConfig = new AppConfig(null, null);

        // When
        ConfigPaths paths = appConfig.getModel();

        // Then
        assertNotNull(paths);
        assertEquals("", paths.getGlobalPath());
        assertEquals("", paths.getProjectPath());
    }

    @Test
    void getProcessName_should_return_default_when_absent() {
        // Given
        AppConfig appConfig = new AppConfig(null, null);

        // When / Then
        assertEquals(AppConfig.DEFAULT_PROCESS_NAME, appConfig.getProcessName());
    }

    @Test
    void getProcessName_should_return_default_when_configured_blank() {
        // Given
        AppConfig appConfig = new AppConfig("   ", null);

        // When / Then
        assertEquals(AppConfig.DEFAULT_PROCESS_NAME, appConfig.getProcessName());
    }

    @Test
    void config_path_should_point_to_classpath_config() {
        assertEquals("classpath:config.json", AppConfig.CONFIG_PATH);
    }

    @Test
    void deserialization_should_bind_configured_values() {
        // Given
        String json = "{\"processName\":\"Custom\",\"model\":{\"globalPath\":\"g.json\",\"projectPath\":\"p.json\"}}";

        // When
        AppConfig appConfig = ObjectMapperWrapper.readValue(json, AppConfig.class);

        // Then
        assertEquals("Custom", appConfig.getProcessName());
        assertEquals("g.json", appConfig.getModel().getGlobalPath());
        assertEquals("p.json", appConfig.getModel().getProjectPath());
    }
}
