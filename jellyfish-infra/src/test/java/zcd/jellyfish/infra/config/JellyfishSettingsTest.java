package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishSettings} 的单元测试：验证插件段缺省与反序列化。
 *
 * @author zcd
 */
class JellyfishSettingsTest {

    @Test
    void getPlugins_should_return_empty_object_when_null() {
        // When
        JellyfishSettings settings = new JellyfishSettings(null);

        // Then
        assertTrue(settings.getPlugins().isEmpty());
        assertTrue(settings.isEmpty());
    }

    @Test
    void getPlugins_should_return_same_instance_when_given() {
        // Given
        PluginsSettings plugins = new PluginsSettings(Collections.singletonList("plugins"), null, null, null);

        // When
        JellyfishSettings settings = new JellyfishSettings(plugins);

        // Then
        assertSame(plugins, settings.getPlugins());
    }

    @Test
    void deserialization_should_bind_plugins_section() {
        // Given
        String json = "{\"plugins\":{\"disabled\":[\"plugin-b\"],"
                + "\"configurations\":{\"plugin-a\":{\"readOnlyTools\":[\"read_file\"]}}}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-b"), settings.getPlugins().getDisabled());
        assertEquals(Collections.singletonList("read_file"),
                settings.getPlugins().getConfigurations().get("plugin-a").get("readOnlyTools"));
    }

    @Test
    void deserialization_should_ignore_unknown_sections() {
        // Given：模型段已迁到 models.json，jellyfish.json 里出现它属于历史残留，应被忽略而不是报错
        String json = "{\"defaultProvider\":\"openai\",\"plugins\":{\"disabled\":[\"plugin-b\"]}}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertEquals(Collections.singletonList("plugin-b"), settings.getPlugins().getDisabled());
    }

    @Test
    void deserialization_should_tolerate_empty_object() {
        // Given
        String json = "{}";

        // When
        JellyfishSettings settings = ObjectMapperWrapper.readValue(json, JellyfishSettings.class);

        // Then
        assertTrue(settings.isEmpty());
    }
}
