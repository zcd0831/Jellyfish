package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginClasspathGuard} 的单元测试：验证三种打包违规都能在加载期被识别。
 *
 * @author zcd
 */
class PluginClasspathGuardTest {

    /** 测试用临时目录。 */
    @TempDir
    Path tempDir;

    @Test
    void check_should_accept_clean_plugin_directory() throws IOException {
        // Given
        Path pluginDir = tempDir.resolve("clean");
        Files.createDirectories(pluginDir.resolve("com/acme"));
        Files.write(pluginDir.resolve("com/acme/SamplePlugin.class"), new byte[0]);
        JellyfishPluginDescriptor descriptor = new JellyfishPluginDescriptor();

        // When
        PluginClasspathGuard.check(pluginDir, descriptor);

        // Then
        assertFalse(descriptor.hasLoadErrors());
    }

    @Test
    void check_should_report_error_when_api_packaged_in_directory() throws IOException {
        // Given
        Path pluginDir = tempDir.resolve("bundled-api");
        Path apiEntry = pluginDir.resolve("zcd/jellyfish/api/PluginContext.class");
        Files.createDirectories(apiEntry.getParent());
        Files.write(apiEntry, new byte[0]);
        JellyfishPluginDescriptor descriptor = new JellyfishPluginDescriptor();

        // When
        PluginClasspathGuard.check(pluginDir, descriptor);

        // Then
        assertTrue(descriptor.hasLoadErrors());
        assertTrue(descriptor.getLoadErrors().get(0).contains("zcd/jellyfish/api/"));
    }

    @Test
    void check_should_report_error_when_pf4j_packaged_in_directory() throws IOException {
        // Given
        Path pluginDir = tempDir.resolve("bundled-pf4j");
        Path pf4jEntry = pluginDir.resolve("org/pf4j/Plugin.class");
        Files.createDirectories(pf4jEntry.getParent());
        Files.write(pf4jEntry, new byte[0]);
        JellyfishPluginDescriptor descriptor = new JellyfishPluginDescriptor();

        // When
        PluginClasspathGuard.check(pluginDir, descriptor);

        // Then
        assertTrue(descriptor.hasLoadErrors());
        assertTrue(descriptor.getLoadErrors().get(0).contains("org/pf4j/"));
    }

    @Test
    void check_should_report_error_when_api_packaged_in_archive() throws IOException {
        // Given
        Path jar = tempDir.resolve("sample.jar");
        writeJar(jar, "plugin.properties", "zcd/jellyfish/api/PluginContext.class");
        JellyfishPluginDescriptor descriptor = new JellyfishPluginDescriptor();

        // When
        PluginClasspathGuard.check(jar, descriptor);

        // Then
        assertTrue(descriptor.hasLoadErrors());
    }

    @Test
    void check_should_report_each_violation_once() throws IOException {
        // Given：多个条目命中同一前缀，只应报一条，避免诊断输出被刷屏
        Path pluginDir = tempDir.resolve("many");
        for (String name : new String[] {"PluginContext.class", "JellyfishPlugin.class"}) {
            Path entry = pluginDir.resolve("zcd/jellyfish/api").resolve(name);
            Files.createDirectories(entry.getParent());
            Files.write(entry, new byte[0]);
        }
        JellyfishPluginDescriptor descriptor = new JellyfishPluginDescriptor();

        // When
        PluginClasspathGuard.check(pluginDir, descriptor);

        // Then
        assertTrue(descriptor.getLoadErrors().size() == 1);
    }

    /**
     * 写入一个只含指定条目的压缩包。
     *
     * @param jar    目标路径
     * @param names  条目名
     * @throws IOException 写入失败时抛出
     */
    private static void writeJar(Path jar, String... names) throws IOException {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            for (String name : names) {
                jarOutput.putNextEntry(new JarEntry(name));
                if (name.endsWith(".properties")) {
                    jarOutput.write("plugin.id=sample\n".getBytes(StandardCharsets.UTF_8));
                }
                jarOutput.closeEntry();
            }
        }
    }
}
