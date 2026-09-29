package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolCaller} 的单元测试：把调用结果映射成内核的工具结果。
 * <p>
 * 用替身 {@link McpInvoker}（而不是真连接）验证映射：这里要钉的是「协议层的失败要变成
 * 界面上的警示标记」这条约定，与消息怎么送出去无关。
 *
 * @author zcd
 */
@DisplayName("MCP 工具处理器")
class McpToolCallerTest {

    @Test
    @DisplayName("成功时回灌正文，摘要写清 server / 工具 / 耗时")
    void handle_should_returnTextAndSummary() {
        // Given
        McpToolCaller caller = new McpToolCaller(definition("read_file"), success("内容", 12L), 1000L);

        // When
        ToolCallResult result = caller.handle(new ToolCallRequest("mcp__fs__read_file",
                Collections.<String, Object>emptyMap(), "s1"));

        // Then
        assertEquals("内容", result.getOutput());
        assertEquals("mcp__fs__read_file", result.getToolName());
        assertTrue(String.valueOf(result.getMetadata().get(ToolMetadata.KEY_SUMMARY)).contains("read_file"));
        assertFalse(ToolMetadata.failed(result.getMetadata()));
    }

    @Test
    @DisplayName("server 报告失败时首行写结论，并带上终止标记")
    void handle_should_flagServerReportedFailure() {
        // Given
        McpToolCaller caller = new McpToolCaller(definition("boom"), failure("炸了", 5L), 1000L);

        // When
        ToolCallResult result = caller.handle(new ToolCallRequest("mcp__fs__boom",
                Collections.<String, Object>emptyMap(), "s1"));

        // Then：协议成功但工具失败，必须能在界面上看出来
        assertTrue(result.getOutput().toString().startsWith("[MCP 工具报告失败]"));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
    }

    @Test
    @DisplayName("原始工具名应传给连接，而不是内核里的展开名")
    void handle_should_passOriginalToolName() {
        // Given
        RecordingInvoker invoker = new RecordingInvoker();
        McpToolCaller caller = new McpToolCaller(definition("read_file"), invoker, 1000L);

        // When
        caller.handle(new ToolCallRequest("mcp__fs__read_file",
                Collections.<String, Object>singletonMap("path", "/tmp/a"), "s1"));

        // Then
        assertEquals("read_file", invoker.lastToolName);
        assertEquals(1000L, invoker.lastTimeoutMillis);
    }

    /**
     * 构造工具定义。
     *
     * @param originalName 原始名
     * @return 定义
     */
    private static McpToolDefinition definition(String originalName) {
        return new McpToolDefinition("fs", originalName, "mcp__fs__" + originalName, "描述",
                Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), false);
    }

    /**
     * 构造成功结果。
     *
     * @param text           文本
     * @param durationMillis 耗时
     * @return 结果
     */
    private static McpInvoker success(String text, long durationMillis) {
        return (name, arguments, timeout, token) -> new McpInvoker.McpCallOutcome(text, false, durationMillis);
    }

    /**
     * 构造 server 报告失败的结果。
     *
     * @param text           文本
     * @param durationMillis 耗时
     * @return 结果
     */
    private static McpInvoker failure(String text, long durationMillis) {
        return (name, arguments, timeout, token) -> new McpInvoker.McpCallOutcome(text, true, durationMillis);
    }

    /**
     * 记录最后一次调用的替身发起方。
     */
    private static final class RecordingInvoker implements McpInvoker {

        /** 最后一次的原始工具名。 */
        private String lastToolName;

        /** 最后一次的超时。 */
        private long lastTimeoutMillis;

        @Override
        public McpCallOutcome callTool(String originalToolName, Map<String, Object> arguments,
                                       long timeoutMillis,
                                       zcd.jellyfish.api.extension.CancellationToken cancellationToken) {
            this.lastToolName = originalToolName;
            this.lastTimeoutMillis = timeoutMillis;
            return new McpCallOutcome("ok", false, 1L);
        }
    }
}
