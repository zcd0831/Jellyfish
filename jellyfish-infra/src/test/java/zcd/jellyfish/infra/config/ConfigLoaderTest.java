package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigLoader} 的单元测试：验证应用配置读取与缺省回退，替换掉真实文件 IO。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ConfigLoaderTest {

    /** 只 mock 文本读取，绑定器使用真实实现。 */
    @Mock
    SettingsReader settingsReader;

    /** 被测读取门面。 */
    private ConfigLoader configLoader;

    @BeforeEach
    void setUp() {
        configLoader = new ConfigLoader(settingsReader, new SettingsBinder(name -> null));
    }

    @Test
    void loadAppConfig_should_return_default_when_config_missing() {
        // Given
        when(settingsReader.read(AppConfig.CONFIG_PATH)).thenReturn(null);

        // When
        AppConfig appConfig = configLoader.loadAppConfig();

        // Then
        assertEquals(AppConfig.DEFAULT_PROCESS_NAME, appConfig.getProcessName());
    }

    @Test
    void loadAppConfig_should_bind_config_when_present() {
        // Given
        when(settingsReader.read(AppConfig.CONFIG_PATH))
                .thenReturn("{\"processName\":\"Custom\",\"model\":{\"globalPath\":\"g.json\"}}");

        // When
        AppConfig appConfig = configLoader.loadAppConfig();

        // Then
        assertEquals("Custom", appConfig.getProcessName());
        assertEquals("g.json", appConfig.getModel().getGlobalPath());
    }

    @Test
    void read_should_return_null_when_path_blank() {
        // When / Then
        assertNull(configLoader.read("  ", AppConfig.class));
        verifyNoInteractions(settingsReader);
    }

    @Test
    void read_should_return_null_when_file_missing() {
        // Given
        when(settingsReader.read("missing.json")).thenReturn(null);

        // When / Then
        assertNull(configLoader.read("missing.json", ModelSettings.class));
    }
}
