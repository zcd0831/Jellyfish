package zcd.jellyfish.plugin.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 客户端与<b>真实子进程</b>之间的端到端测试。
 * <p>
 * <b>为什么它不在 {@code mvn test} 里</b>：它会 fork 进程，而本项目的约定是单测不访问外部资源。
 * 用 profile（{@code -Pmcp-it}）而不是把它做成单测的第二个原因：这里启动的是仓库自带的
 * {@link EchoMcpServer}，因此不需要用户机器上装任何东西——它守的是本插件的分帧、握手、
 * 应答配对、stderr 排空与关停这一整条链路，而不是某个第三方 server 的兼容性。
 *
 * @author zcd
 */
@DisplayName("MCP 客户端与真实子进程")
class McpStdioTransportIT {

    /** 临时目录，用作二进制落盘根。 */
    @TempDir
    Path tempDir;

    /** 被测连接。 */
    private McpServerConnection connection;

    /** 共享状态。 */
    private McpRegistry registry;

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("应连上真实的 server 进程、拿到工具清单")
    void connect_should_talkToRealProcess() {
        // Given
        List<List<McpToolDefinition>> received = new ArrayList<List<McpToolDefinition>>();

        // When
        connection = connect(received);

        // Then
        assertEquals(1, received.size());
        List<McpToolDefinition> tools = received.get(0);
        assertEquals(2, tools.size());
        assertEquals("mcp__echo__echo", tools.get(0).qualifiedName());
        assertTrue(tools.get(0).readOnly());
        assertFalse(tools.get(1).readOnly());
        assertEquals(McpRegistry.State.CONNECTED, registry.statusOf("echo").state());
    }

    @Test
    @DisplayName("调用应真的往返一次，并把 isError 如实带出来")
    void callTool_should_roundTrip() {
        // Given
        List<List<McpToolDefinition>> received = new ArrayList<List<McpToolDefinition>>();
        connection = connect(received);

        // When
        McpInvoker.McpCallOutcome echoed = connection.callTool("echo",
                Collections.<String, Object>singletonMap("text", "你好"), 5000L, CancellationToken.NONE);
        McpInvoker.McpCallOutcome failed = connection.callTool("boom",
                Collections.<String, Object>emptyMap(), 5000L, CancellationToken.NONE);

        // Then
        assertEquals("echo:你好", echoed.text());
        assertFalse(echoed.error());
        assertTrue(failed.error());
        assertTrue(failed.text().contains("工具自己说失败了"));
    }

    @Test
    @DisplayName("连接关闭后调用应失败，而不是挂住")
    void callTool_should_failAfterClose() {
        // Given
        List<List<McpToolDefinition>> received = new ArrayList<List<McpToolDefinition>>();
        connection = connect(received);

        // When
        connection.close();

        // Then
        try {
            connection.callTool("echo", Collections.<String, Object>emptyMap(), 1000L,
                    CancellationToken.NONE);
            org.junit.jupiter.api.Assertions.fail("关闭之后调用应当失败");
        } catch (JellyfishException expected) {
            assertTrue(expected.getMessage().contains("未连接"));
        }
    }

    /**
     * 连上仓库自带的 echo server。
     *
     * @param received 工具清单的接收容器
     * @return 连接
     */
    private McpServerConnection connect(List<List<McpToolDefinition>> received) {
        Map<String, Object> server = new HashMap<String, Object>();
        server.put("id", "echo");
        server.put("command", javaExecutable());
        server.put("args", new ArrayList<String>(javaCommandLine()));
        server.put("connectTimeoutSeconds", 20);
        server.put("callTimeoutSeconds", 10);
        McpConfig global = McpConfig.from(new HashMap<String, Object>());
        registry = new McpRegistry();
        registry.register("echo", McpRegistry.State.PENDING, "");

        McpServerConnection created = new McpServerConnection(McpServerConfig.from(server), global,
                registry, received::add, new McpMediaSpill(tempDir));
        created.connect();
        return created;
    }

    /**
     * 取当前 JVM 的 java 可执行文件。
     *
     * @return 路径
     */
    private static String javaExecutable() {
        return System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + "java";
    }

    /**
     * 组装启动 echo server 的命令行参数。
     * <p>
     * 直接用当前进程的 classpath：surefire 的 fork 里它就是测试类路径，
     * 因此 {@link EchoMcpServer} 与它依赖的 Jackson 都在。
     *
     * @return 参数列表
     */
    private static List<String> javaCommandLine() {
        List<String> args = new ArrayList<String>();
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.add(EchoMcpServer.class.getName());
        return args;
    }
}
