package zcd.jellyfish.plugin.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node 语言适配的单元测试。
 * <p>
 * 这里钉住的是三条<b>一旦改动就会静默出错</b>的约定：
 * 环境变量白名单（放宽等于把 JVM 环境整份交给不受信子进程）、
 * 启动命令里入口必须是绝对路径（否则子进程按自己的 cwd 找脚本，会莫名找不到）、
 * 以及网关资源清单必须包含那五个文件（少一个的表现是「脚本工具调用时才失败」）。
 * <p>
 * 与 Python 侧的差异也写在这里：Node 不需要「关缓冲」的环境变量，因为协议通道的
 * 同步写在 {@code script/*.js} 里解决；因此白名单里没有对应的键。
 *
 * @author zcd
 */
@DisplayName("Node 语言适配")
class NodeLanguageTest {

    /** 白名单的独立副本：与实现各写一份，改宽实现会被这条用例挡住。 */
    private static final Set<String> EXPECTED_WHITELIST = new HashSet<String>(Arrays.asList(
            "PATH", "HOME", "LANG", "NODE_PATH", "NODE_OPTIONS", "NODE_ENV", "NPM_CONFIG_PREFIX"));

    @Test
    @DisplayName("语言标识与人读名应稳定")
    void idAndDisplayName_should_beStable() {
        NodeLanguage language = new NodeLanguage("node");

        assertEquals("node", language.id());
        assertEquals("Node", language.displayName());
    }

    @Test
    @DisplayName("探测命令应带版本旗标且不做任何副作用")
    void probeCommand_should_askForVersion_when_interpreterIsConfigured() {
        NodeLanguage language = new NodeLanguage("/opt/node/bin/node");

        assertEquals(Arrays.asList("/opt/node/bin/node", "--version"), language.probeCommand());
    }

    @Test
    @DisplayName("启动命令应指向网关目录下的绝对入口")
    void startCommand_should_pointToAbsoluteGatewayEntry_when_directoryIsGiven() {
        Path gatewayDirectory = Paths.get("/tmp/jellyfish-gateway");

        List<String> command = new NodeLanguage("node").startCommand(gatewayDirectory);

        assertEquals(Arrays.asList("node", "/tmp/jellyfish-gateway/script/gateway.js"), command);
    }

    @Test
    @DisplayName("网关资源清单应包含控制面、数据面、分帧、SDK 与离线生成器")
    void gatewayResources_should_coverGatewayWorkerWireSdkAndDumper() {
        List<String> resources = new NodeLanguage("node").gatewayResources();

        assertTrue(resources.contains("script/gateway.js"), resources.toString());
        assertTrue(resources.contains("script/worker.js"), resources.toString());
        assertTrue(resources.contains("script/script_wire.js"), resources.toString());
        assertTrue(resources.contains("script/jellyfish_sdk.js"), resources.toString());
        assertTrue(resources.contains("script/dump_manifest.js"), resources.toString());
        // 入口必须是清单里的一项，否则「抽出来的资源」与「启动的命令」会对不上
        assertTrue(resources.get(0).endsWith("gateway.js"), resources.toString());
    }

    @Test
    @DisplayName("环境变量应只保留白名单项")
    void environment_should_onlyKeepWhitelistedVariables() {
        Map<String, String> environment = new NodeLanguage("node").environment();

        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            boolean allowed = EXPECTED_WHITELIST.contains(name) || name.startsWith("LC_");
            assertTrue(allowed, "非白名单环境变量被透传: " + name);
        }
    }

    @Test
    @DisplayName("环境变量映射应不可变，避免调用方误改后影响下一次启动")
    void environment_should_beImmutable() {
        Map<String, String> environment = new NodeLanguage("node").environment();

        assertFalse(environment.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> environment.put("INJECTED", "1"));
    }

    @Test
    @DisplayName("toString 应带出解释器路径，便于排查「用的是哪个 node」")
    void toString_should_mentionInterpreterPath() {
        assertTrue(new NodeLanguage("/usr/local/bin/node").toString().contains("/usr/local/bin/node"));
    }
}
