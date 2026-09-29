package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpPermissionContribution} 的单元测试：只读免打扰、写类工具要人看一眼、不越界管别人。
 *
 * @author zcd
 */
@DisplayName("MCP 默认权限裁定")
class McpPermissionContributionTest {

    @Test
    @DisplayName("别人的工具一律无异议：类型级扩展点会收到每一次工具调用的权限检查")
    void handle_should_abstainForOtherTools() {
        // Given
        McpRegistry registry = new McpRegistry();
        McpPermissionContribution contribution = contribution(registry, true);

        // When / Then
        assertTrue(contribution.handle(request("read_file")).isAbstain());
        assertTrue(contribution.handle(request("mcp__fs__unknown")).isAbstain());
    }

    @Test
    @DisplayName("只读工具无异议：读一份东西不该每次都弹批准框")
    void handle_should_abstainForReadOnly() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        registry.replaceTools("fs", Collections.singletonList(
                new McpToolDefinition("fs", "read_file", "mcp__fs__read_file", "d",
                        Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), true)));

        // When
        PermissionVerdict verdict = contribution(registry, true).handle(request("mcp__fs__read_file"));

        // Then
        assertTrue(verdict.isAbstain());
    }

    @Test
    @DisplayName("未声明只读的工具应要求审批")
    void handle_should_askForWritableTools() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        registry.replaceTools("fs", Collections.singletonList(
                new McpToolDefinition("fs", "write", "mcp__fs__write", "d",
                        Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), false)));

        // When
        PermissionVerdict verdict = contribution(registry, true).handle(request("mcp__fs__write"));

        // Then
        assertTrue(verdict.isAsk());
        assertTrue(verdict.getReason().contains("mcp__fs__write"));
    }

    @Test
    @DisplayName("关掉开关后一律无异议：便利机制要能整个关掉")
    void handle_should_abstainWhen_askingDisabled() {
        // Given
        McpRegistry registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        registry.replaceTools("fs", Collections.singletonList(
                new McpToolDefinition("fs", "write", "mcp__fs__write", "d",
                        Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), false)));

        // When / Then
        assertTrue(contribution(registry, false).handle(request("mcp__fs__write")).isAbstain());
    }

    /**
     * 构造处理器。
     *
     * @param registry     共享状态
     * @param askWriteTools 是否开启审批
     * @return 处理器
     */
    private static McpPermissionContribution contribution(McpRegistry registry, boolean askWriteTools) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(McpConfig.KEY_ASK_WRITE_TOOLS, Boolean.valueOf(askWriteTools));
        return new McpPermissionContribution(registry, McpConfig.from(values));
    }

    /**
     * 构造权限检查请求。
     *
     * @param toolName 工具名
     * @return 请求
     */
    private static PermissionCheckRequest request(String toolName) {
        return new PermissionCheckRequest("default", toolName, Collections.<String, Object>emptyMap(),
                PermissionMode.NORMAL, "s1");
    }
}
