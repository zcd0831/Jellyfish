package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.plugin.PluginDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishPluginDescriptor} 的单元测试：验证声明转换与只读发布。
 * <p>
 * 描述符由解析器构造（{@code plugin.id} 是 PF4J 的 {@code protected} setter，测试无法直接设置），
 * 因此统一先落盘再解析，顺带覆盖解析结果本身。
 *
 * @author zcd
 */
class JellyfishPluginDescriptorTest {

    /** 测试用插件目录。 */
    @TempDir
    Path pluginDir;

    @Test
    void toDeclaration_should_carry_plugin_id() throws IOException {
        // Given
        JellyfishPluginDescriptor descriptor = descriptor("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n");

        // When
        PluginDeclaration declaration = descriptor.toDeclaration(null);

        // Then
        assertEquals("sample", declaration.getPluginId());
    }

    @Test
    void toDeclaration_should_carry_configuration() throws IOException {
        // Given
        JellyfishPluginDescriptor descriptor = descriptor("plugin.id=sample\nplugin.class=com.acme.SamplePlugin\n");
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("precision", 4);

        // When
        PluginDeclaration declaration = descriptor.toDeclaration(configuration);

        // Then
        assertEquals(4, declaration.getConfiguration().get("precision"));
    }

    @Test
    void getters_should_return_unmodifiable_collections() throws IOException {
        // Given
        JellyfishPluginDescriptor descriptor = descriptor("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n"
                + "jellyfish.tags=default\n");
        descriptor.addLoadError("boom");

        // Then
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getTags().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.getLoadErrors().add("x"));
    }

    @Test
    void hasLoadErrors_should_be_false_when_no_error_recorded() throws IOException {
        // Given
        JellyfishPluginDescriptor descriptor = descriptor("plugin.id=sample\nplugin.class=com.acme.SamplePlugin\n");

        // Then
        assertTrue(descriptor.getLoadErrors().isEmpty());
        assertFalse(descriptor.hasLoadErrors());
    }

    /**
     * 落盘描述符并解析。
     *
     * @param properties 描述符内容
     * @return 解析结果
     * @throws IOException 写入失败时抛出
     */
    private JellyfishPluginDescriptor descriptor(String properties) throws IOException {
        Files.write(pluginDir.resolve(PluginProperties.FILE_NAME),
                properties.getBytes(StandardCharsets.UTF_8));
        return (JellyfishPluginDescriptor) new JellyfishPluginDescriptorFinder().find(pluginDir);
    }
}
