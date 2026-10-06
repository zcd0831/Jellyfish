package zcd.jellyfish.cli.mode;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.di.DaggerJellyfishComponent;
import zcd.jellyfish.di.JellyfishComponent;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.server.JellyfishServer;
import zcd.jellyfish.server.ServerConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server 模式的端到端用例：真 Undertow + 真内核（Dagger 装配），走本机回环。
 * <p>
 * <b>为什么放在 cli 模块</b>：只有这里有 composition root。{@code jellyfish-server} 里能测到
 * 处理器层，但「路由真的接上了、真的能被 HTTP 客户端访问」只有起一个真服务才能证明。
 * <p>
 * <b>为什么端口用 0</b>：由系统分配，避免与开发机上已在跑的服务撞端口；{@link JellyfishServer#boundPort()}
 * 把实际端口读回来。
 * <p>
 * <b>不依赖模型配置</b>：命令与健康检查无需 apiKey；对话那一条只断言「流以某个终态事件结束」，
 * 因此配没配模型都能过——末尾那句「回合失败」是环境差异，不是本用例要测的东西。
 *
 * @author zcd
 */
class ServerEndToEndIT {

    /** 连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    /** 读取超时（毫秒）。 */
    private static final int READ_TIMEOUT_MS = 10000;

    @Test
    void server_should_expose_sessions_commands_health_and_stream_over_http() throws IOException {
        JellyfishComponent component = DaggerJellyfishComponent.create();
        AgentHarness harness = component.agentHarness();
        harness.bootstrap();
        JellyfishServer server = new JellyfishServer(ServerConfig.builder("127.0.0.1", 0).build(),
                component.conversationService(), component.sessionManager(), component.commandManager(), component.agentManager(),
                component.modelManager(), component.approvalChannel(), component.askChannel(),
                component.healthCheck(), component.turnRegistry(),
                component.shellStreams(), component.runEventBus());
        server.start();
        int boundPort = server.boundPort();
        try {
            String base = "http://127.0.0.1:" + boundPort;

            // 1. 健康检查：唯一保证「永远能答」的接口
            Response health = get(base + "/health");
            assertEquals(200, health.status);
            assertTrue(health.body.contains("\"status\""), health.body);

            // 2. 建会话 → 列表里能看到
            Response created = post(base + "/sessions", "{}");
            assertEquals(201, created.status);
            String sessionId = extract(created.body, "\"sessionId\":\"");
            assertTrue(sessionId != null && !sessionId.isEmpty(), created.body);
            Response list = get(base + "/sessions");
            assertEquals(200, list.status);
            assertTrue(list.body.contains(sessionId), list.body);

            // 3. 命令执行：/help 不需要模型配置
            Response help = post(base + "/sessions/" + sessionId + "/commands", "{\"input\":\"/help\"}");
            assertEquals(200, help.status);
            assertTrue(help.body.contains("\"kind\":\"OK\""), help.body);

            // 4. 命令清单里必须有 help
            Response commands = get(base + "/commands");
            assertEquals(200, commands.status);
            assertTrue(commands.body.contains("\"name\":\"help\""), commands.body);

            // 5. 无候选的命令返回空数组而不是报错
            Response options = get(base + "/commands/help/options");
            assertEquals(200, options.status);
            assertTrue(options.body.contains("[]"), options.body);

            // 6. 对话是 SSE：有 turn_start，且以某个终态事件收尾
            Response chat = post(base + "/sessions/" + sessionId + "/chat", "{\"message\":\"你好\"}");
            assertEquals(200, chat.status);
            assertTrue(chat.body.contains("event: turn_start"), chat.body);
            assertTrue(chat.body.contains("event: done") || chat.body.contains("event: error")
                    || chat.body.contains("event: cancelled"), chat.body);

            // 7. 未知会话 → 404；未知审批 → 404
            assertEquals(404, get(base + "/sessions/nope").status);
            assertEquals(404, post(base + "/approvals/nope", "{\"approved\":true}").status);

            // 8. 删除 → 204，再取 → 404
            assertEquals(204, delete(base + "/sessions/" + sessionId).status);
            assertEquals(404, get(base + "/sessions/" + sessionId).status);
        } finally {
            server.stop();
            harness.shutdown();
        }
        // 9. 停掉之后端口确实释放：可以再次绑同一端口（用新服务验证）
        JellyfishServer restarted = new JellyfishServer(
                ServerConfig.builder("127.0.0.1", boundPort).build(),
                component.conversationService(), component.sessionManager(), component.commandManager(), component.agentManager(),
                component.modelManager(), component.approvalChannel(), component.askChannel(),
                component.healthCheck(), component.turnRegistry(),
                component.shellStreams(), component.runEventBus());
        try {
            restarted.start();
            assertEquals(200, get("http://127.0.0.1:" + restarted.boundPort() + "/health").status);
        } finally {
            restarted.stop();
        }
    }

    @Test
    void server_should_require_api_key_when_configured() throws IOException {
        JellyfishComponent component = DaggerJellyfishComponent.create();
        AgentHarness harness = component.agentHarness();
        harness.bootstrap();
        JellyfishServer server = new JellyfishServer(
                ServerConfig.builder("127.0.0.1", 0).apiKey("s3cret-api-key").build(),
                component.conversationService(), component.sessionManager(), component.commandManager(), component.agentManager(),
                component.modelManager(), component.approvalChannel(), component.askChannel(),
                component.healthCheck(), component.turnRegistry(),
                component.shellStreams(), component.runEventBus());
        server.start();
        String base = "http://127.0.0.1:" + server.boundPort();
        try {
            // 探活不需要密钥：它是编排器与「起服务后的第一条 curl」唯一能用的接口
            assertEquals(200, get(base + "/health").status);

            // 其余接口：没带、带错、方案不对一律 401
            assertEquals(401, get(base + "/commands").status);
            assertEquals(401, call(base + "/commands", "GET", null, "Bearer wrong").status);
            assertEquals(401, call(base + "/commands", "GET", null, "s3cret-api-key").status);

            // 带上正确密钥才通
            assertEquals(200, call(base + "/commands", "GET", null, "Bearer s3cret-api-key").status);
            // 写接口同样受保护：不能只保护读的那几个
            assertEquals(401, post(base + "/sessions", "{}").status);
            assertEquals(201, callPost(base + "/sessions", "{}", "Bearer s3cret-api-key").status);
        } finally {
            server.stop();
            harness.shutdown();
        }
    }

    /**
     * 发一次 GET。
     *
     * @param url 完整地址
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response get(String url) throws IOException {
        return call(url, "GET", null);
    }

    /**
     * 发一次 POST（JSON 体）。
     *
     * @param url  完整地址
     * @param body 请求体
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response post(String url, String body) throws IOException {
        return call(url, "POST", body);
    }

    /**
     * 发一次带鉴权头的 POST。
     *
     * @param url         完整地址
     * @param body        请求体
     * @param apiKeyValue {@code Authorization} 头的值
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response callPost(String url, String body, String apiKeyValue) throws IOException {
        return call(url, "POST", body, apiKeyValue);
    }

    /**
     * 发一次 DELETE。
     *
     * @param url 完整地址
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response delete(String url) throws IOException {
        return call(url, "DELETE", null);
    }

    /**
     * 发一次请求并读回状态码与响应体。
     *
     * @param url    完整地址
     * @param method HTTP 方法
     * @param body   请求体，可为 {@code null}
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response call(String url, String method, String body) throws IOException {
        return call(url, method, body, null);
    }

    /**
     * 发一次带鉴权头的 GET。
     *
     * @param url         完整地址
     * @param method      HTTP 方法
     * @param body        请求体，可为 {@code null}
     * @param apiKeyValue {@code Authorization} 头的值，可为 {@code null}
     * @return 响应
     * @throws IOException 连接失败
     */
    private static Response call(String url, String method, String body, String apiKeyValue) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        if (apiKeyValue != null) {
            connection.setRequestProperty("Authorization", apiKeyValue);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = in == null ? "" : read(in);
        connection.disconnect();
        return new Response(status, text);
    }

    /**
     * 读尽输入流。
     *
     * @param in 输入流
     * @return UTF-8 文本
     * @throws IOException 读取失败
     */
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 从 JSON 文本里抠出某个字符串字段的第一个取值（仅用于测试，不做完整解析）。
     *
     * @param json   JSON 文本
     * @param prefix 字段前缀（如 {@code "sessionId":"}）
     * @return 字段值；找不到时返回 {@code null}
     */
    private static String extract(String json, String prefix) {
        int start = json.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        int from = start + prefix.length();
        int end = json.indexOf('"', from);
        return end < 0 ? null : json.substring(from, end);
    }

    /**
     * 一次 HTTP 响应。
     *
     * @author zcd
     */
    private static final class Response {

        /** 状态码。 */
        private final int status;

        /** 响应体。 */
        private final String body;

        /**
         * 构造响应。
         *
         * @param status 状态码
         * @param body   响应体
         */
        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
