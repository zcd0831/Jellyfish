package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void read_should_report_unknown_fields_when_they_exist() {
        // Given：两份配置，一份字段全对、一份把 defaultModel 拼错了
        when(settingsReader.read("ok.json")).thenReturn("{\"defaultModel\": \"m\"}");
        when(settingsReader.read("typo.json")).thenReturn("{\"defaultModle\": \"m\"}");
        List<String> reported = new ArrayList<String>();

        // When / Then：字段全对的一个都不报
        configLoader.read("ok.json", ModelSettings.class, reported::add);
        assertEquals(Collections.emptyList(), reported);

        // When / Then：拼错的那个报出来（绑定本身不会报——它容忍未知字段）
        configLoader.read("typo.json", ModelSettings.class, reported::add);
        assertEquals(Collections.singletonList("defaultModle"), reported);
    }

    @Test
    void read_should_not_report_when_reporting_not_requested() {
        // Given
        when(settingsReader.read("typo.json")).thenReturn("{\"defaultModle\": \"m\"}");

        // When / Then：不传接收方的那条路（内置资源走的就是它）什么都不报、也不抛
        assertNotNull(configLoader.read("typo.json", ModelSettings.class));
    }
}
