package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 Java 写的「协议回声」子进程，专供 {@link CommonsExecScriptProcessTest} 使用。
 * <p>
 * <b>为什么要有一个假网关</b>：{@code ScriptRpc} 与 {@code ScriptGateway} 都能用内存假进程单测，
 * 但「真实管道能否承载逐行 JSON」「环境变量是否被完整替换」「最后一行没有换行时会不会丢」
 * 这三件事只有真的起一个进程才能验证。用 Java 写这个假网关，就把「验证协议实现」与
 * 「验证 Python 环境」解耦——前者属于 {@code mvn test}，后者属于需要解释器的独立 profile。
 * <p>
 * 它不是生产代码，因此只实现测试需要的四种行为：正常应答、打印脏行、正常退出、赖着不走。
 * <p>
 * 用法：{@code java -cp <testClasspath> zcd.jellyfish.script.EchoGatewayProcess [mode]}，
 * 其中 {@code mode} 为 {@code normal}（默认）、{@code noise}（先打一行非 JSON 到 stdout、一行日志到 stderr）
 * 或 {@code ignore-shutdown}（收到 shutdown 也不退出）。
 *
 * @author zcd
 */
public final class EchoGatewayProcess {

    /** 运行的模式。 */
    private static String mode = "normal";

    /**
     * 工具类，禁止实例化。
     */
    private EchoGatewayProcess() {
    }

    /**
     * 子进程入口：逐行读协议、逐行答。
     *
     * @param args 命令行参数，可选的第一个参数是运行模式
     * @throws Exception 读写失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            mode = args[0];
        }
        // stdout 只走协议：这里显式拿原始字节流，避免 PrintStream 的自动刷新与编码干扰
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                false, "UTF-8");
        if ("noise".equals(mode)) {
            // 模拟用户脚本误打印与解释器警告：协议实现必须宽容丢弃，且不影响后续帧
            out.println("这是脚本误打印的一行");
            System.err.println("这是脚本的调试输出（中文与非 ASCII：✓）");
            out.flush();
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String response = respond(line);
            if (response != null) {
                out.println(response);
                out.flush();
            }
            if ("shutdown".equals(methodOf(line)) && !"ignore-shutdown".equals(mode)) {
                return;
            }
        }
        System.err.println("stdin 已关闭，结束时最后一行没有换行");
    }

    /**
     * 处理一帧请求。
     *
     * @param line 请求帧
     * @return 应答帧；无需应答时为 {@code null}
     */
    private static String respond(String line) {
        String method = methodOf(line);
        long id = idOf(line);
        if ("initialize".equals(method)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            java.util.List<Map<String, Object>> scripts = new java.util.ArrayList<Map<String, Object>>();
            Map<String, Object> script = new LinkedHashMap<String, Object>();
            script.put("script", "echo");
            script.put("ok", Boolean.TRUE);
            scripts.add(script);
            payload.put("scripts", scripts);
            payload.put("marker", System.getenv("JELLYFISH_TEST_MARKER"));
            payload.put("home", System.getenv("HOME"));
            return frame(id, payload);
        }
        if ("invoke".equals(method)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("output", paramsOf(line));
            return frame(id, payload);
        }
        if ("status".equals(method)) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("state", "running");
            return frame(id, payload);
        }
        if ("shutdown".equals(method)) {
            return frame(id, new LinkedHashMap<String, Object>());
        }
        return null;
    }

    /**
     * 取请求里的方法名。
     *
     * @param line 请求帧
     * @return 方法名；解析不出时为 {@code null}
     */
    private static String methodOf(String line) {
        try {
            JsonNode node = ScriptJson.tree(line).get("method");
            return node == null || node.isNull() ? null : node.asText();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 取请求里的 id。
     *
     * @param line 请求帧
     * @return id；解析不出时为 {@code 0}
     */
    private static long idOf(String line) {
        try {
            return ScriptJson.tree(line).path("id").asLong(0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * 取请求里的参数，原样回显。
     *
     * @param line 请求帧
     * @return 参数字符串
     */
    private static String paramsOf(String line) {
        try {
            return ScriptJson.tree(line).path("params").toString();
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    /**
     * 拼一帧应答。
     *
     * @param id      请求 id
     * @param payload 结果载荷
     * @return 应答帧
     */
    private static String frame(long id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", Long.valueOf(id));
        envelope.put("result", payload);
        return ScriptJson.write(envelope);
    }
}
