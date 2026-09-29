package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpServerConnection} 的协议行为测试：握手、能力声明、工具清单、调用、以及 server 反向请求。
 * <p>
 * 用内存传输驱动，不起任何真进程：这里测的是协议逻辑，而不是管道能不能用。
 *
 * @author zcd
 */
@DisplayName("MCP 连接协议行为")
class McpServerConnectionTest {

    /** 临时目录，用作二进制落盘根。 */
    @TempDir
    Path tempDir;

    /** 内存传输。 */
    private FakeTransport transport;

    /** 共享状态。 */
    private McpRegistry registry;

    /** 收到的工具清单。 */
    private final List<List<McpToolDefinition>> received =
            Collections.synchronizedList(new ArrayList<List<McpToolDefinition>>());

    /** 被测连接。 */
    private McpServerConnection connection;

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    @DisplayName("握手应声明 roots 能力，且不声明 sampling / elicitation")
    void connect_should_declareRootsOnly() {
        // Given
        connection = connected(2);

        // When
        ObjectNode initialize = transport.lastRequest(McpProtocol.METHOD_INITIALIZE);

        // Then
        assertNotNull(initialize);
        ObjectNode capabilities = (ObjectNode) initialize.get("params").get("capabilities");
        assertTrue(capabilities.has("roots"));
        // 声明了却办不到比不声明更糟：server 会按「客户端支持」去规划它的行为
        assertTrue(!capabilities.has("sampling"));
        assertTrue(!capabilities.has("elicitation"));
        // 握手之后必须发这条通知，否则不少 server 会一直等
        assertNotNull(transport.lastRequest(McpProtocol.METHOD_INITIALIZED));
    }

    @Test
    @DisplayName("工具清单应被映射成带前缀的内核工具名，并带上只读标记")
    void connect_should_mapTools() {
        // When
        connection = connected(2);

        // Then
        assertEquals(1, received.size());
        List<McpToolDefinition> tools = received.get(0);
        assertEquals(2, tools.size());
        assertEquals("mcp__fake__read_file", tools.get(0).qualifiedName());
        assertEquals("read_file", tools.get(0).originalName());
        assertTrue(tools.get(0).readOnly());
        assertEquals(Collections.singletonList("path"), tools.get(0).required());
        assertTrue(tools.get(0).parameters().containsKey("path"));
        // 没有 hint 的工具缺省按可写处理
        assertTrue(!tools.get(1).readOnly());
    }

    @Test
    @DisplayName("调用应回传原始工具名，并把 isError 如实带出来")
    void callTool_should_sendOriginalNameAndReportError() {
        // Given
        connection = connected(2);

        // When
        McpInvoker.McpCallOutcome ok = connection.callTool("read_file",
                Collections.<String, Object>singletonMap("path", "/tmp/x"), 2000L, CancellationToken.NONE);
        McpInvoker.McpCallOutcome failed = connection.callTool("boom",
                Collections.<String, Object>emptyMap(), 2000L, CancellationToken.NONE);

        // Then
        assertEquals("done", ok.text());
        assertTrue(!ok.error());
        assertTrue(failed.error());

        ObjectNode call = transport.lastRequest(McpProtocol.METHOD_TOOLS_CALL);
        assertNotNull(call);
        // 发回去的必须是 server 认得的原始名，而不是内核里的展开名
        assertEquals("boom", call.get("params").get("name").asText());
    }

    @Test
    @DisplayName("调用超时应抛错")
    void callTool_should_throw_when_timeout() {
        // Given：先正常连上，再把 tools/call 的应答停掉（不回 = 只能超时）
        connection = connected(1);
        transport.responder(message -> McpProtocol.METHOD_TOOLS_CALL.equals(
                McpJson.text(message, "method", null)) ? null : defaultResponder(message));

        // When / Then
        assertThrows(JellyfishException.class, () -> connection.callTool("read_file",
                Collections.<String, Object>emptyMap(), 200L, CancellationToken.NONE));
    }

    @Test
    @DisplayName("server 发来的 sampling 请求应被明确回绝，而不是不理")
    void handleServerRequest_should_refuseSampling() throws InterruptedException {
        // Given
        connection = connected(2);

        // When
        transport.push("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"sampling/createMessage\","
                + "\"params\":{}}");
        waitForReply(99L);

        // Then
        ObjectNode reply = replyWithId(99L);
        assertNotNull(reply);
        assertEquals(McpProtocol.ERROR_METHOD_NOT_FOUND, reply.get("error").get("code").asInt());
        assertTrue(reply.get("error").get("message").asText().contains("sampling/createMessage"));
    }

    @Test
    @DisplayName("roots/list 应被回答成进程工作目录")
    void handleServerRequest_should_answerRootsList() throws InterruptedException {
        // Given
        connection = connected(2);

        // When
        transport.push("{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"" + McpProtocol.METHOD_ROOTS_LIST
                + "\",\"params\":{}}");
        waitForReply(77L);

        // Then
        ObjectNode reply = replyWithId(77L);
        assertNotNull(reply);
        assertTrue(reply.get("result").get("roots").get(0).get("uri").asText().startsWith("file:"));
    }

    @Test
    @DisplayName("tools/list_changed 通知应触发重扫并再次交给注册方")
    void toolsListChanged_should_refresh() throws InterruptedException {
        // Given
        connection = connected(2);
        assertEquals(1, received.size());

        // When
        transport.push("{\"jsonrpc\":\"2.0\",\"method\":\""
                + McpProtocol.METHOD_TOOLS_LIST_CHANGED + "\"}");

        // Then：刷新跑在读线程上，因此这里等它把清单交出来
        long deadline = System.currentTimeMillis() + 3000L;
        while (received.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertEquals(2, received.size());
    }

    @Test
    @DisplayName("解析不了的一行应被丢弃，后续消息照常处理")
    void dispatch_should_ignoreUnparsableLine() throws InterruptedException {
        // Given
        connection = connected(2);

        // When
        transport.push("这不是 JSON");
        transport.push("{\"jsonrpc\":\"2.0\",\"id\":55,\"method\":\"" + McpProtocol.METHOD_PING
                + "\",\"params\":{}}");
        waitForReply(55L);

        // Then：坏行没有毒化后续处理
        assertNotNull(replyWithId(55L));
    }

    @Test
    @DisplayName("连接失败应记入共享状态，而不是静默什么都不发生")
    void connect_should_noteFailure_when_handshakeFails() {
        // Given：initialize 直接回错误
        Map<String, Object> values = serverConfig(2);
        McpServerConfig server = McpServerConfig.from(values);
        transport = new FakeTransport();
        transport.responder(message -> {
            long id = message.get("id").asLong();
            return FakeTransport.error(id, -32600, "unsupported");
        });
        registry = new McpRegistry();
        registry.register("fake", McpRegistry.State.PENDING, "");
        connection = new McpServerConnection(server, globalConfig(), registry,
                tools -> received.add(tools), new McpMediaSpill(tempDir), config -> transport);

        // When / Then
        assertThrows(JellyfishException.class, () -> connection.connect());
        assertEquals(McpRegistry.State.FAILED, registry.statusOf("fake").state());
    }

    /**
     * 建一条已连接的连接。
     *
     * @param callTimeoutSeconds 调用超时秒数
     * @return 连接
     */
    private McpServerConnection connected(int callTimeoutSeconds) {
        Map<String, Object> values = serverConfig(callTimeoutSeconds);
        McpServerConfig server = McpServerConfig.from(values);
        transport = new FakeTransport();
        transport.responder(this::defaultResponder);
        registry = new McpRegistry();
        registry.register("fake", McpRegistry.State.PENDING, "");
        connection = new McpServerConnection(server, globalConfig(), registry,
                tools -> received.add(tools), new McpMediaSpill(tempDir), config -> transport);
        connection.connect();
        return connection;
    }

    /**
     * 默认应答：握手成功，两个工具，调用成功（名字为 {@code boom} 时报告失败）。
     *
     * @param message 请求消息
     * @return 应答文本；{@code null} 表示不回
     */
    private String defaultResponder(ObjectNode message) {
        String method = McpJson.text(message, "method", "");
        long id = message.get("id").asLong();
        if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
            return FakeTransport.result(id, "{\"protocolVersion\":\""
                    + McpProtocol.PROTOCOL_VERSION + "\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}");
        }
        if (McpProtocol.METHOD_TOOLS_LIST.equals(method)) {
            return FakeTransport.result(id, "{\"tools\":["
                    + "{\"name\":\"read_file\",\"description\":\"读文件\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                    + "{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]},"
                    + "\"annotations\":{\"readOnlyHint\":true}},"
                    + "{\"name\":\"write_thing\",\"description\":\"写东西\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}");
        }
        if (McpProtocol.METHOD_TOOLS_CALL.equals(method)) {
            String name = McpJson.text(message.get("params"), "name", "");
            if ("boom".equals(name)) {
                return FakeTransport.result(id,
                        "{\"content\":[{\"type\":\"text\",\"text\":\"炸了\"}],\"isError\":true}");
            }
            return FakeTransport.result(id, "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}");
        }
        return null;
    }

    /**
     * 构造服务配置。
     *
     * @param callTimeoutSeconds 调用超时秒数
     * @return 配置映射
     */
    private static Map<String, Object> serverConfig(int callTimeoutSeconds) {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("id", "fake");
        values.put("command", "echo");
        values.put("connectTimeoutSeconds", 2);
        values.put("callTimeoutSeconds", callTimeoutSeconds);
        return values;
    }

    /**
     * 构造全局配置。
     *
     * @return 配置
     */
    private static McpConfig globalConfig() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("toolPrefix", Boolean.TRUE);
        values.put("toolNameMaxLength", 64);
        return McpConfig.from(values);
    }

    /**
     * 从已发出的消息里找指定 id 的应答。
     *
     * @param id 应答 id
     * @return 消息节点；没找到时返回 {@code null}
     */
    private ObjectNode replyWithId(long id) {
        for (String line : transport.sentRaw()) {
            ObjectNode message = McpJson.parseObject(line);
            if (message.has("id") && message.get("id").asLong() == id && !message.has("method")) {
                return message;
            }
        }
        return null;
    }

    /**
     * 等待指定 id 的应答出现。
     *
     * @param id 应答 id
     * @throws InterruptedException 等待被中断时抛出
     */
    private void waitForReply(long id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (replyWithId(id) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertNotNull(replyWithId(id), "应答未在超时前发出");
    }
}
