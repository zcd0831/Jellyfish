package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpConfig} 与 {@link McpServerConfig} 的单元测试：默认值、server 解析与非法配置的报错。
 *
 * @author zcd
 */
@DisplayName("mcp 插件配置")
class McpConfigTest {

    @Test
    @DisplayName("整段缺省时用默认值，且没有任何 server")
    void from_should_useDefaults_when_configurationEmpty() {
        // When
        McpConfig config = McpConfig.from(null);

        // Then
        assertTrue(config.servers().isEmpty());
        assertTrue(config.toolPrefix());
        assertEquals(64, config.toolNameMaxLength());
        assertTrue(config.askWriteTools());
        assertEquals(5, config.startupWaitSeconds());
        assertEquals(200, config.maxToolsPerServer());
    }

    @Test
    @DisplayName("server 的 args / env / readOnlyTools / enabled 都应被读进来")
    void from_should_parseServerFields() {
        // Given
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "fs");
        server.put("command", "npx");
        server.put("args", Arrays.asList("-y", "@modelcontextprotocol/server-filesystem"));
        server.put("env", Collections.singletonMap("TOKEN", "abc"));
        server.put("readOnlyTools", Arrays.asList("read_file"));
        server.put("callTimeoutSeconds", 30);
        McpConfig config = McpConfig.from(configWith(server));

        // When
        McpServerConfig parsed = config.servers().get(0);

        // Then
        assertEquals("fs", parsed.id());
        assertEquals("npx", parsed.command());
        assertEquals(2, parsed.args().size());
        assertEquals("abc", parsed.env().get("TOKEN"));
        assertTrue(parsed.isDeclaredReadOnly("read_file"));
        assertFalse(parsed.isDeclaredReadOnly("write_file"));
        assertEquals(30, parsed.callTimeoutSeconds());
        assertEquals(McpServerConfig.DEFAULT_CONNECT_TIMEOUT_SECONDS, parsed.connectTimeoutSeconds());
        assertTrue(parsed.enabled());
    }

    @Test
    @DisplayName("enabledServers 只返回启用的那些")
    void enabledServers_should_filterDisabled() {
        // Given
        Map<String, Object> enabled = new HashMap<String, Object>();
        enabled.put("id", "a");
        enabled.put("command", "echo");
        Map<String, Object> disabled = new HashMap<String, Object>();
        disabled.put("id", "b");
        disabled.put("command", "echo");
        disabled.put("enabled", Boolean.FALSE);
        McpConfig config = McpConfig.from(configWith(enabled, disabled));

        // When / Then
        assertEquals(1, config.enabledServers().size());
        assertEquals("a", config.enabledServers().get(0).id());
        assertEquals(2, config.servers().size());
    }

    @Test
    @DisplayName("缺少 id 或 command 应报错：这两项没有可推断的默认值")
    void from_should_rejectMissingRequiredFields() {
        // Given
        Map<String, Object> noCommand = new HashMap<String, Object>();
        noCommand.put("id", "a");
        Map<String, Object> noId = new HashMap<String, Object>();
        noId.put("command", "echo");

        // When / Then
        assertThrows(JellyfishException.class, () -> McpConfig.from(configWith(noCommand)));
        assertThrows(JellyfishException.class, () -> McpConfig.from(configWith(noId)));
    }

    @Test
    @DisplayName("重复的 server id 应报错：它会变成同一个 owner 命名空间与同一段工具名前缀")
    void from_should_rejectDuplicateIds() {
        // Given
        Map<String, Object> first = new HashMap<String, Object>();
        first.put("id", "same");
        first.put("command", "echo");
        Map<String, Object> second = new HashMap<String, Object>();
        second.put("id", "same");
        second.put("command", "echo");

        // When / Then
        assertThrows(JellyfishException.class, () -> McpConfig.from(configWith(first, second)));
    }

    @Test
    @DisplayName("id 含空白或路径分隔符应报错：它要成为 owner 子命名空间")
    void from_should_rejectInvalidServerId() {
        // Given
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "a b");
        server.put("command", "echo");

        // When / Then
        assertThrows(JellyfishException.class, () -> McpConfig.from(configWith(server)));
    }

    @Test
    @DisplayName("超时与上限越界应报错，而不是静默夹到边界")
    void from_should_rejectOutOfRangeValues() {
        // Given
        Map<String, Object> shortName = new HashMap<String, Object>();
        shortName.put("toolNameMaxLength", 4);
        Map<String, Object> negativeTimeout = new HashMap<String, Object>();
        negativeTimeout.put("startupWaitSeconds", -1);

        // When / Then
        assertThrows(JellyfishException.class, () -> McpConfig.from(shortName));
        assertThrows(JellyfishException.class, () -> McpConfig.from(negativeTimeout));
    }

    @Test
    @DisplayName("单个 server 的负超时应报错；0 是合法取值（不超时）")
    void serverTimeouts_should_allowZeroButRejectNegative() {
        // Given
        Map<String, Object> zero = new HashMap<String, Object>();
        zero.put("id", "a");
        zero.put("command", "echo");
        zero.put("callTimeoutSeconds", 0);
        Map<String, Object> negative = new HashMap<String, Object>();
        negative.put("id", "a");
        negative.put("command", "echo");
        negative.put("connectTimeoutSeconds", -5);

        // When / Then
        assertEquals(0, McpServerConfig.from(zero).callTimeoutSeconds());
        assertThrows(JellyfishException.class, () -> McpServerConfig.from(negative));
    }

    /**
     * 组装一份带 servers 的配置映射。
     *
     * @param servers server 配置项
     * @return 配置映射
     */
    @SafeVarargs
    private static Map<String, Object> configWith(Map<String, Object>... servers) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_SERVERS, new ArrayList<Map<String, Object>>(Arrays.asList(servers)));
        return values;
    }
}
