package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpLedger} 的单元测试：台账必须把「连不上」与「为什么」说清楚。
 * <p>
 * 这是 {@code /mcp} 存在的全部理由——MCP 的现场表现几乎全是「工具没出现」，
 * 而真正的原因只在这里可见。
 *
 * @author zcd
 */
@DisplayName("MCP 台账")
class McpLedgerTest {

    @Test
    @DisplayName("没有 server 时应明确说出来，并指出配置键")
    void render_should_sayNoServerConfigured() {
        // When
        String text = McpLedger.render(config(Collections.<String, Object>emptyMap()), new McpRegistry());

        // Then
        assertTrue(text.contains("没有配置任何 MCP server"));
        assertTrue(text.contains("plugins.configurations.jellyfish-mcp.servers"));
    }

    @Test
    @DisplayName("应报告每个 server 的状态、工具数与失败原因")
    void render_should_reportServerState() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "已注册 1 个工具");
        registry.replaceTools("fs", Collections.singletonList(new McpToolDefinition("fs", "read_file",
                "mcp__fs__read_file", "d", Collections.<String, Object>emptyMap(),
                Collections.<String>emptyList(), true)));
        registry.register("remote", McpRegistry.State.FAILED, "启动失败");
        registry.noteFailure("remote", "npx: command not found");
        registry.noteLateResponse("remote");

        // When
        String text = McpLedger.render(configWithServers(), registry);

        // Then
        assertTrue(text.contains("fs（已连接）"));
        assertTrue(text.contains("remote（失败）"));
        assertTrue(text.contains("最近错误: npx: command not found"));
        assertTrue(text.contains("迟到应答（已丢弃）: 1"));
        assertTrue(text.contains("- mcp__fs__read_file（只读）"));
    }

    @Test
    @DisplayName("工具清单超长时应截断并说明还有多少个")
    void render_should_truncateLongToolList() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        java.util.List<McpToolDefinition> tools = new java.util.ArrayList<McpToolDefinition>();
        for (int index = 0; index < 80; index++) {
            tools.add(new McpToolDefinition("fs", "t" + index, "mcp__fs__t" + index, "d",
                    Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), false));
        }
        registry.replaceTools("fs", tools);

        // When
        String text = McpLedger.render(configWithServers(), registry);

        // Then
        assertTrue(text.contains("另有"));
    }

    /**
     * 构造只带一个 server 的配置。
     *
     * @return 配置
     */
    private static McpConfig configWithServers() {
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "fs");
        server.put("command", "echo");
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_SERVERS, Collections.singletonList(server));
        return McpConfig.from(values);
    }

    /**
     * 构造配置。
     *
     * @param values 配置映射
     * @return 配置
     */
    private static McpConfig config(Map<String, Object> values) {
        return McpConfig.from(values);
    }
}
