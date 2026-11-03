package io.github.zcd0831.jellyfish.infrastructure.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动技术清理：只管文件层面的残留。
 * 规则出处：09-infrastructure §9.7。
 */
class FileSystemCleanerTest {

    @TempDir
    Path tmp;

    private DataDirectory dir() {
        return DataDirectory.of(tmp.toString());
    }

    @Test
    void 未完成的临时文件直接删除() throws Exception {
        Path sessionDir = dir().sessionDir("s1");
        Files.createDirectories(sessionDir);
        Files.write(sessionDir.resolve("meta.json.tmp"), "{}".getBytes(StandardCharsets.UTF_8));

        new FileSystemCleaner(dir()).clean();

        assertFalse(Files.exists(sessionDir.resolve("meta.json.tmp")));
    }

    @Test
    void 记录文件末尾半行被截断到最后一条完整行() throws Exception {
        Path sessionDir = dir().sessionDir("s1");
        Files.createDirectories(sessionDir);
        Path records = sessionDir.resolve("records-0001.jsonl");
        Files.write(records, "{\"a\":1}\n{\"a\":2}\n{\"a\":".getBytes(StandardCharsets.UTF_8));

        new FileSystemCleaner(dir()).clean();

        assertEquals("{\"a\":1}\n{\"a\":2}\n", new String(Files.readAllBytes(records), StandardCharsets.UTF_8));
    }

    @Test
    void 已归档会话目录被删除() throws Exception {
        Path sessionDir = dir().sessionDir("s1");
        Files.createDirectories(sessionDir.resolve("sub/c1"));
        Files.createDirectories(dir().archive());
        Files.write(dir().archive().resolve("s1.zip"), new byte[]{1});

        new FileSystemCleaner(dir()).clean();

        assertFalse(Files.exists(sessionDir));
        assertTrue(Files.exists(dir().archive().resolve("s1.zip")));
    }

    @Test
    void 子会话下第二层sub只告警不删除() throws Exception {
        Path nested = dir().sessionDir("s1").resolve("sub/c1/sub/c2");
        Files.createDirectories(nested);

        List<String> warnings = new FileSystemCleaner(dir()).clean();

        assertTrue(warnings.toString().contains("第二层 sub/"), warnings.toString());
        assertTrue(Files.isDirectory(nested), "只告警并忽略，不删除数据");
    }

    @Test
    void 插件目录名与jar内PluginId不一致时告警并忽略() throws Exception {
        Path pluginDir = dir().plugins().resolve("dir-name");
        Files.createDirectories(pluginDir);
        writeJarWithPluginId(pluginDir.resolve("p.jar"), "another-id");

        List<String> warnings = new FileSystemCleaner(dir()).clean();

        assertTrue(warnings.toString().contains("Plugin-Id 不一致"), warnings.toString());
        assertTrue(Files.isDirectory(pluginDir), "忽略不等于删除");
    }

    @Test
    void 插件目录名与jar内PluginId一致时不告警() throws Exception {
        Path pluginDir = dir().plugins().resolve("same-id");
        Files.createDirectories(pluginDir);
        writeJarWithPluginId(pluginDir.resolve("p.jar"), "same-id");

        List<String> warnings = new FileSystemCleaner(dir()).clean();

        assertFalse(warnings.toString().contains("Plugin-Id"), warnings.toString());
    }

    private static void writeJarWithPluginId(Path jar, String pluginId) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Id", pluginId);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new ZipEntry("META-INF/"));
        }
    }
}
