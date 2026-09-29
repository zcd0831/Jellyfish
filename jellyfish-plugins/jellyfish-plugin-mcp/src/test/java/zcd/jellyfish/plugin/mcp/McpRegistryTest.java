package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpRegistry} 的单元测试：工具集整体替换、只读标记与状态记账。
 *
 * @author zcd
 */
@DisplayName("MCP 共享状态")
class McpRegistryTest {

    @Test
    @DisplayName("登记后状态可读，且台账按标识排序")
    void register_should_trackState() {
        // Given
        McpRegistry registry = new McpRegistry();

        // When
        registry.register("b", McpRegistry.State.PENDING, "");
        registry.register("a", McpRegistry.State.CONNECTING, "握手");

        // Then：排序让台账输出稳定，不然每次 /mcp 的顺序都可能不同
        assertEquals("a", registry.snapshot().get(0).serverId());
        assertEquals("b", registry.snapshot().get(1).serverId());
        assertEquals(McpRegistry.State.CONNECTING, registry.statusOf("a").state());
    }

    @Test
    @DisplayName("整体替换应带上只读标记与工具数")
    void replaceTools_should_trackReadOnlyAndCount() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.PENDING, "");

        // When
        registry.replaceTools("fs", Arrays.asList(tool("mcp__fs__read", true), tool("mcp__fs__write", false)));

        // Then
        assertTrue(registry.isMcpTool("mcp__fs__read"));
        assertTrue(registry.isReadOnly("mcp__fs__read"));
        assertFalse(registry.isReadOnly("mcp__fs__write"));
        assertEquals(2, registry.statusOf("fs").toolCount());
        assertEquals(2, registry.toolsOf("fs").size());
    }

    @Test
    @DisplayName("整体替换应把上一次的名字清掉：否则被删掉的工具会永远留在只读表里")
    void replaceTools_should_forgetPreviousNames() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.PENDING, "");
        registry.replaceTools("fs", Collections.singletonList(tool("mcp__fs__old", true)));

        // When
        registry.replaceTools("fs", Collections.singletonList(tool("mcp__fs__new", true)));

        // Then
        assertFalse(registry.isMcpTool("mcp__fs__old"));
        assertTrue(registry.isMcpTool("mcp__fs__new"));
        assertEquals(1, registry.statusOf("fs").toolCount());
    }

    @Test
    @DisplayName("未知工具既不算我们的工具，也不算只读")
    void unknownTool_should_beNeitherOursNorReadOnly() {
        // Given
        McpRegistry registry = new McpRegistry();

        // Then
        assertFalse(registry.isMcpTool("read_file"));
        assertFalse(registry.isReadOnly("read_file"));
        assertFalse(registry.isMcpTool(null));
    }

    @Test
    @DisplayName("removeServer 应把工具与只读标记一起收回")
    void removeServer_should_dropTools() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.PENDING, "");
        registry.replaceTools("fs", Collections.singletonList(tool("mcp__fs__read", true)));

        // When
        registry.removeServer("fs");

        // Then
        assertFalse(registry.isMcpTool("mcp__fs__read"));
        assertTrue(registry.toolsOf("fs").isEmpty());
    }

    @Test
    @DisplayName("失败、告警与迟到应答都应被记下：台账要靠它们解释「工具为什么没了」")
    void diagnostics_should_beRecorded() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.PENDING, "");

        // When
        registry.noteFailure("fs", "进程起不来");
        registry.noteWarning("fs", "工具数超过上限");
        registry.noteLateResponse("fs");
        registry.noteLateResponse("fs");

        // Then
        McpRegistry.ServerStatus status = registry.statusOf("fs");
        assertEquals(McpRegistry.State.FAILED, status.state());
        assertEquals("进程起不来", status.lastError());
        assertEquals("工具数超过上限", status.lastWarning());
        assertEquals(2L, status.lateResponses());
    }

    @Test
    @DisplayName("对未登记的 server 记状态应是空操作而不是抛错")
    void noteState_should_beNoopForUnknownServer() {
        // Given
        McpRegistry registry = new McpRegistry();

        // When / Then
        registry.noteState("ghost", McpRegistry.State.CONNECTED, "x");
        registry.noteFailure("ghost", "x");
        registry.noteLateResponse("ghost");
        registry.noteWarning("ghost", "x");
        assertTrue(registry.snapshot().isEmpty());
    }

    /**
     * 构造一个工具定义。
     *
     * @param qualifiedName 展开名
     * @param readOnly      是否只读
     * @return 定义
     */
    private static McpToolDefinition tool(String qualifiedName, boolean readOnly) {
        List<String> required = Collections.emptyList();
        return new McpToolDefinition("fs", qualifiedName, qualifiedName, "d",
                Collections.<String, Object>emptyMap(), required, readOnly);
    }
}
