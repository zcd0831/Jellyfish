package io.github.zcd0831.jellyfish.infrastructure.plugin;

import io.github.zcd0831.jellyfish.application.shared.port.PluginDescriptor;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PF4J 3.16.0 在 JDK 8 上的真实装卸：
 * 装 → 启 → 读工具声明 → 停 → 卸，且不落任何状态文件。
 */
class Pf4jPluginRuntimeTest {

    private static final String PLUGIN_ID = "demo.echo";

    @TempDir
    Path dir;

    @Test
    void 装载启用读取工具停用卸载且不落状态文件() throws IOException {
        Files.createDirectories(dir.resolve(PLUGIN_ID));
        writePluginJar(dir.resolve(PLUGIN_ID).resolve(PLUGIN_ID + "-1.0.0.jar"));
        Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(dir, new JsonCodec(),
                Collections.<String, Class<?>>emptyMap());

        List<PluginDescriptor> found = runtime.discover();
        assertEquals(1, found.size());
        assertEquals(PLUGIN_ID, found.get(0).id().getValue());
        assertEquals("1.0.0", found.get(0).version());
        assertEquals(7, found.get(0).apiVersion(), "Plugin-Requires 就是 apiVersion");
        assertFalse(found.get(0).loaded());

        assertTrue(runtime.conflictsOf(PluginId.of(PLUGIN_ID)).contains("okhttp3/"),
                "检测插件重复打包的宿主依赖");

        runtime.start(PluginId.of(PLUGIN_ID));
        assertTrue(runtime.discover().get(0).loaded());

        List<ToolDefinition> tools = runtime.toolsOf(PluginId.of(PLUGIN_ID));
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).id().getValue());
        assertEquals(SourceType.PLUGIN, tools.get(0).source().getType());
        assertEquals(PLUGIN_ID + "/echoHandler", tools.get(0).source().getEndpointOrIdentifier());

        runtime.stop(PluginId.of(PLUGIN_ID));
        assertFalse(runtime.discover().get(0).loaded());

        runtime.unload(PluginId.of(PLUGIN_ID));
        Files.delete(dir.resolve(PLUGIN_ID).resolve(PLUGIN_ID + "-1.0.0.jar"));   // 卸载 = 删目录（§7.6 规则 1）
        assertTrue(runtime.discover().isEmpty());

        assertFalse(Files.exists(dir.resolve("enabled.txt")), "关掉 PF4J 状态文件");
        assertFalse(Files.exists(dir.resolve("disabled.txt")), "关掉 PF4J 状态文件");
    }

    @Test
    void 目录名与jar内id不一致的插件不装载() throws IOException {
        Files.createDirectories(dir.resolve("wrong-dir"));
        writePluginJar(dir.resolve("wrong-dir").resolve(PLUGIN_ID + "-1.0.0.jar"));
        Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(dir, new JsonCodec(),
                Collections.<String, Class<?>>emptyMap());

        // 09-infrastructure §9.7：目录名与 jar 内 Plugin-Id 不一致 ⇒ 告警并忽略该目录（不装载）。
        // 否则同一个 id 落在两个目录里，卸载只会删掉一个，另一个成了漏网残留。
        assertTrue(runtime.discover().isEmpty(), "不一致的目录不许装进来");
    }

    @Test
    void 目录名与jar内id一致的插件照常装载() throws IOException {
        Files.createDirectories(dir.resolve(PLUGIN_ID));
        writePluginJar(dir.resolve(PLUGIN_ID).resolve(PLUGIN_ID + "-1.0.0.jar"));
        Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(dir, new JsonCodec(),
                Collections.<String, Class<?>>emptyMap());

        assertEquals(1, runtime.discover().size(), "别把正常插件一起挡了");
    }

    @Test
    void 坏jar只牵连自己且修好后会重装() throws IOException {
        Files.createDirectories(dir.resolve(PLUGIN_ID));
        writePluginJar(dir.resolve(PLUGIN_ID).resolve(PLUGIN_ID + "-1.0.0.jar"));
        Path badDir = dir.resolve("bad");
        Files.createDirectories(badDir);
        Path badJar = badDir.resolve("oops.jar");
        writeJarWithBrokenDescriptor(badJar, "bad");
        Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(dir, new JsonCodec(),
                Collections.<String, Class<?>>emptyMap());

        // 09-infrastructure §9.12 + 05-action §5.8.6：单个插件失败不牵连
        List<PluginDescriptor> found = runtime.discover();
        assertEquals(1, found.size(), "坏包是 Error（ZipError），不能把健康插件一起拖下水: " + found);
        assertEquals(PLUGIN_ID, found.get(0).id().getValue());

        // 修好之后不用重启：坏 jar 不能被永久记成"已装载过"
        Files.delete(badJar);
        writePluginJar(badDir.resolve("oops.jar"), "bad");
        assertEquals(2, runtime.discover().size(), "换掉坏包后应当重新尝试装载");
    }

    @Test
    void 反复取用不泄漏文件描述符() throws Exception {
        Files.createDirectories(dir.resolve(PLUGIN_ID));
        writePluginJar(dir.resolve(PLUGIN_ID).resolve(PLUGIN_ID + "-1.0.0.jar"));
        Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(dir, new JsonCodec(),
                Collections.<String, Class<?>>emptyMap());
        runtime.discover();
        long before = openDescriptors();

        for (int i = 0; i < 200; i++) {
            runtime.discover();
            runtime.toolsOf(PluginId.of(PLUGIN_ID));
        }
        long after = openDescriptors();

        assertTrue(after - before < 30, "200 轮取用后 fd 从 " + before + " 涨到 " + after + "，目录流没关");
    }

    private static long openDescriptors() throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(java.nio.file.Paths.get("/dev/fd"))) {
            return files.count();
        } catch (IOException e) {
            return -1;   // 非 POSIX：跳过（断言会因差值 < 30 通过）
        }
    }

    private static void writePluginJar(Path jar) throws IOException {
        writePluginJar(jar, PLUGIN_ID);
    }

    /** 清单完好、但 plugin.properties 是坏字节（半下载 / 打包错）——解析它就抛，PF4J 与我们的校验都会碰上。 */
    private static void writeJarWithBrokenDescriptor(Path jar, String pluginId) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", pluginId);
        attributes.putValue("Plugin-Class", "jellyfish.Demo");
        attributes.putValue("Plugin-Version", "1.0.0");
        attributes.putValue("Plugin-Requires", "7");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("plugin.properties"));
            zip.write("plugin.id=\\uZZZZ\n".getBytes(StandardCharsets.UTF_8));   // 坏转义 ⇒ 解析即抛
            zip.closeEntry();
        }
    }

    private static void writePluginJar(Path jar, String pluginId) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", pluginId);
        attributes.putValue("Plugin-Version", "1.0.0");
        attributes.putValue("Plugin-Requires", "7");
        attributes.putValue("Plugin-Provider", "test");
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(Pf4jPluginRuntime.TOOLS_PREFIX + "echo.json"));
            zip.write(echoTool().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("okhttp3/OkHttpClient.class"));   // 故意重复打包宿主依赖
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
    }

    private static String echoTool() {
        return "{\"id\":\"echo\",\"name\":\"echo\",\"description\":\"回显\","
                + "\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{\"type\":\"object\"},"
                + "\"source\":{\"type\":\"PLUGIN\",\"endpointOrIdentifier\":\"echoHandler\"},"
                + "\"sideEffect\":\"READ_ONLY\",\"timeoutMillis\":1000,\"status\":\"ACTIVE\"}";
    }
}
