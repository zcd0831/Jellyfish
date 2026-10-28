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

    private static void writePluginJar(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", PLUGIN_ID);
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
