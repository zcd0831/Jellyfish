package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishPluginDescriptorFinder} 的单元测试：验证私有键解析与两处加固。
 * <p>
 * 加固对应两条实测结论：{@code plugin.class} 缺失时必须报错（父类默认值是 {@code org.pf4j.Plugin}，
 * 按「值非空」校验会放过去），非法的 {@code plugin.requires} 必须归一为通配而不能留给 PF4J 抛异常。
 *
 * @author zcd
 */
class JellyfishPluginDescriptorFinderTest {

    /** 测试用插件目录。 */
    @TempDir
    Path pluginDir;

    @Test
    void find_should_parse_jellyfish_keys_when_present() throws IOException {
        // Given / When
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n"
                + "plugin.version=1.0.0\n"
                + "jellyfish.tags=default, finance\n");

        // Then
        assertEquals("sample", descriptor.getPluginId());
        assertEquals("com.acme.SamplePlugin", descriptor.getPluginClass());
        assertTrue(descriptor.getTags().containsAll(java.util.Arrays.asList("default", "finance")));
        assertFalse(descriptor.hasLoadErrors());
    }

    @Test
    void find_should_return_empty_sets_when_jellyfish_keys_absent() throws IOException {
        // Given / When
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n");

        // Then
        assertTrue(descriptor.getTags().isEmpty());
    }

    @Test
    void find_should_report_load_error_when_plugin_class_missing() throws IOException {
        // Given / When：父类会把 plugin.class 兜底成 org.pf4j.Plugin，因此必须校验「键存在」
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\nplugin.version=1.0.0\n");

        // Then
        assertTrue(descriptor.hasLoadErrors());
        assertTrue(descriptor.getLoadErrors().get(0).contains(PluginProperties.PLUGIN_CLASS));
    }

    @Test
    void find_should_default_version_when_missing() throws IOException {
        // Given / When
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\nplugin.class=com.acme.SamplePlugin\n");

        // Then
        assertEquals(JellyfishPluginDescriptor.FALLBACK_VERSION, descriptor.getVersion());
    }

    @Test
    void find_should_normalize_requires_when_expression_invalid() throws IOException {
        // Given / When：带 -SNAPSHOT 的表达式会让 semver 解析抛异常，必须在此归一，不能留给加载循环
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n"
                + "plugin.requires=0.0.1-SNAPSHOT\n");

        // Then
        assertEquals("*", descriptor.getRequires());
        assertTrue(descriptor.hasLoadErrors());
        assertTrue(descriptor.getLoadErrors().get(0).contains(PluginProperties.PLUGIN_REQUIRES));
    }

    @Test
    void find_should_keep_requires_when_expression_valid() throws IOException {
        // Given / When
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\n"
                + "plugin.class=com.acme.SamplePlugin\n"
                + "plugin.requires=>=0.0.1\n");

        // Then
        assertEquals(">=0.0.1", descriptor.getRequires());
        assertFalse(descriptor.hasLoadErrors());
    }

    @Test
    void find_should_report_load_error_when_kernel_contract_bundled() throws IOException {
        // Given：插件自带内核契约会让子优先的类加载器遮蔽父加载器同名类
        Path apiEntry = pluginDir.resolve("zcd/jellyfish/api/PluginContext.class");
        Files.createDirectories(apiEntry.getParent());
        Files.write(apiEntry, new byte[0]);

        // When
        JellyfishPluginDescriptor descriptor = find("plugin.id=sample\nplugin.class=com.acme.SamplePlugin\n");

        // Then
        assertTrue(descriptor.hasLoadErrors());
        assertTrue(descriptor.getLoadErrors().get(0).contains("zcd/jellyfish/api/"));
    }

    /**
     * 在测试目录写入描述符并解析。
     *
     * @param properties 描述符内容
     * @return 解析结果
     * @throws IOException 写入失败时抛出
     */
    private JellyfishPluginDescriptor find(String properties) throws IOException {
        Files.write(pluginDir.resolve(PluginProperties.FILE_NAME),
                properties.getBytes(StandardCharsets.UTF_8));
        PluginDescriptor descriptor = new JellyfishPluginDescriptorFinder().find(pluginDir);
        return (JellyfishPluginDescriptor) descriptor;
    }
}
