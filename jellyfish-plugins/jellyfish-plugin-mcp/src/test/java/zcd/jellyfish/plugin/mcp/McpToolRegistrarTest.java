package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpToolRegistrar} 的单元测试：整体替换、按 server 归因、以及冲突时的隔离。
 *
 * @author zcd
 */
@DisplayName("MCP 工具注册器")
class McpToolRegistrarTest {

    /** 共用注册表。 */
    private TypeRegistry typeRegistry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    /** 插件上下文。 */
    private PluginContext context;

    /** 共享状态。 */
    private McpRegistry registry;

    /** 被测注册器。 */
    private McpToolRegistrar registrar;

    /** 替身发起方。 */
    private final McpInvoker invoker =
            (name, arguments, timeout, token) -> new McpInvoker.McpCallOutcome("ok", false, 1L);

    @BeforeEach
    void setUp() {
        typeRegistry = new TypeRegistry();
        extensions = new ExtensionRegistry(typeRegistry);
        events = new EventChannel(EventChannelOptions.defaults(), typeRegistry);
        events.start();
        context = new PluginContextImpl(PluginDeclaration.of("jellyfish-mcp"), extensions, events);
        registry = new McpRegistry();
        registry.register("fs", McpRegistry.State.CONNECTED, "");
        registrar = new McpToolRegistrar(context, registry);
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("应把工具注册到 <插件>::<server> 的子命名空间下")
    void apply_should_registerUnderServerNamespace() {
        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("read_file", true), tool("write", false)));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__read_file").size());
        assertEquals("jellyfish-mcp::fs", typeRegistry.resolve(ToolCallRequest.class, "mcp__fs__write")
                .get(0).getOwner());
        assertTrue(registry.isReadOnly("mcp__fs__read_file"));
    }

    @Test
    @DisplayName("再次 apply 应整体替换：名字变了的那一个必须消失")
    void apply_should_replacePreviousTools() {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("old", true)));

        // When
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("new", true)));

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__fs__old").isEmpty());
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__new").size());
        assertTrue(!registry.isMcpTool("mcp__fs__old"));
    }

    @Test
    @DisplayName("注册冲突应只跳过那一个工具，其余照常可用")
    void apply_should_skipConflictingToolOnly() {
        // Given：先占住一个名字，模拟关掉前缀时撞上内置工具
        extensions.handle("core", ToolCallRequest.class, "mcp__fs__taken", null,
                request -> new ToolCallResult("mcp__fs__taken", "builtin"),
                zcd.jellyfish.api.event.RegisterOptions.DEFAULT);

        // When
        registrar.apply(invoker, server("fs"), Arrays.asList(tool("taken", true), tool("free", true)));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "mcp__fs__free").size());
        assertTrue(registry.statusOf("fs").lastWarning().contains("mcp__fs__taken"));
    }

    @Test
    @DisplayName("close 应注销该 server 的全部工具")
    void close_should_unregisterServerTools() {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("read_file", true)));

        // When
        registrar.close("fs");

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__fs__read_file").isEmpty());
        assertTrue(!registry.isMcpTool("mcp__fs__read_file"));
    }

    @Test
    @DisplayName("closeAll 应把全部 server 的工具都收干净")
    void closeAll_should_unregisterEverything() {
        // Given
        registrar.apply(invoker, server("a"), Collections.singletonList(tool("t", true)));
        registrar.apply(invoker, server("b"), Collections.singletonList(tool("t", true)));

        // When
        registrar.closeAll();

        // Then
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__a__t").isEmpty());
        assertTrue(extensions.handlers(ToolCallRequest.class, "mcp__b__t").isEmpty());
        assertTrue(typeRegistry.snapshot().isEmpty());
    }

    @Test
    @DisplayName("注册出来的处理器应真的能把调用转给发起方")
    void apply_should_wireCallerToInvoker() throws Exception {
        // Given
        registrar.apply(invoker, server("fs"), Collections.singletonList(tool("read_file", true)));

        // When
        ToolCallResult result = extensions.<ToolCallRequest, ToolCallResult>handler(
                        ToolCallRequest.class, "mcp__fs__read_file")
                .handle(new ToolCallRequest("mcp__fs__read_file", Collections.<String, Object>emptyMap(), "s1"));

        // Then
        assertEquals("ok", result.getOutput());
    }

    /**
     * 构造服务配置。
     *
     * @param id 服务标识
     * @return 服务配置
     */
    private static McpServerConfig server(String id) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("id", id);
        values.put("command", "echo");
        values.put("callTimeoutSeconds", 5);
        return McpServerConfig.from(values);
    }

    /**
     * 构造工具定义。
     *
     * @param originalName 原始名
     * @param readOnly     是否只读
     * @return 定义
     */
    private static McpToolDefinition tool(String originalName, boolean readOnly) {
        List<String> required = Collections.emptyList();
        return new McpToolDefinition("fs", originalName, "mcp__fs__" + originalName, "d",
                Collections.<String, Object>emptyMap(), required, readOnly);
    }
}
