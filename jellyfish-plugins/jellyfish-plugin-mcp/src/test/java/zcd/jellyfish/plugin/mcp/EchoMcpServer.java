package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 测试用的极简 MCP server：仓库内自带、零外部依赖（只用 JDK）。
 * <p>
 * <b>为什么不用一个真的第三方 server</b>：那要求用户的机器上装了它（{@code npx}、python、网络……），
 * 而端到端用例的价值恰恰在于「换一台机器也能跑」。这里要验证的是本插件与<b>真实子进程</b>
 * 之间的那段链路——分帧、握手、应答配对、stderr 排空、关停杀进程——这些与对面是谁无关。
 * <p>
 * 由 {@code McpStdioTransportIT} 以 {@code java -cp <test classpath>} 的方式拉起。
 *
 * @author zcd
 */
public final class EchoMcpServer {

    /** 协议版本。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /**
     * 工具类，禁止实例化。
     */
    private EchoMcpServer() {
    }

    /**
     * 入口：逐行读请求、逐行写应答。
     *
     * @param args 未使用
     * @throws Exception 读写失败时抛出
     */
    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream output = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        // 故意往 stderr 写一行：真实的 server 都会这么做，而它绝不能混进 stdout 的 JSON 流
        System.err.println("echo-mcp: started");
        String line;
        while ((line = input.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            handle(line, output);
        }
        System.err.println("echo-mcp: stdin closed, exiting");
    }

    /**
     * 处理一条消息。
     *
     * @param line   消息文本
     * @param output 输出流
     */
    private static void handle(String line, PrintStream output) {
        ObjectNode message;
        try {
            message = McpJson.parseObject(line);
        } catch (RuntimeException e) {
            return;
        }
        String method = McpJson.text(message, "method", "");
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return;
        }
        long requestId = id.asLong();
        if (McpProtocol.METHOD_INITIALIZE.equals(method)) {
            respond(output, requestId, "{\"protocolVersion\":\"" + PROTOCOL_VERSION
                    + "\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"echo-mcp\","
                    + "\"version\":\"1.0\"}}");
            return;
        }
        if (McpProtocol.METHOD_TOOLS_LIST.equals(method)) {
            respond(output, requestId, toolsJson());
            return;
        }
        if (McpProtocol.METHOD_TOOLS_CALL.equals(method)) {
            callTool(output, requestId, message.get("params"));
            return;
        }
        if (McpProtocol.METHOD_PING.equals(method)) {
            respond(output, requestId, "{}");
            return;
        }
        respondError(output, requestId, McpProtocol.ERROR_METHOD_NOT_FOUND, "unknown method");
    }

    /**
     * 处理一次工具调用。
     *
     * @param output    输出流
     * @param id        请求 id
     * @param params    参数
     */
    private static void callTool(PrintStream output, long id, JsonNode params) {
        String name = McpJson.text(params, "name", "");
        if ("boom".equals(name)) {
            // 协议成功但工具报告失败：这正是「isError 不是异常」那条约定的现场
            respond(output, id, "{\"content\":[{\"type\":\"text\",\"text\":\"工具自己说失败了\"}],"
                    + "\"isError\":true}");
            return;
        }
        String echoed = McpJson.text(params.get("arguments"), "text", "");
        respond(output, id, "{\"content\":[{\"type\":\"text\",\"text\":\"echo:" + echoed + "\"}]}");
    }

    /**
     * 工具清单：一个只读工具、一个可写工具、一个会报告失败的工具。
     *
     * @return 结果 JSON
     */
    private static String toolsJson() {
        return "{\"tools\":["
                + "{\"name\":\"echo\",\"description\":\"回显文本\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                + "{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]},"
                + "\"annotations\":{\"readOnlyHint\":true}},"
                + "{\"name\":\"boom\",\"description\":\"总是报告失败\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
    }

    /**
     * 写一条成功应答。
     *
     * @param output 输出流
     * @param id     请求 id
     * @param result 结果 JSON
     */
    private static void respond(PrintStream output, long id, String result) {
        output.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
    }

    /**
     * 写一条错误应答。
     *
     * @param output  输出流
     * @param id      请求 id
     * @param code    错误码
     * @param message 错误信息
     */
    private static void respondError(PrintStream output, long id, int code, String message) {
        output.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}");
    }
}
