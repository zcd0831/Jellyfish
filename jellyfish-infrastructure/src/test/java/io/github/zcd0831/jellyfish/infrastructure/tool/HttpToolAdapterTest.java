package io.github.zcd0831.jellyfish.infrastructure.tool;

import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REST 工具出站调用的成功 / 失败 / 异常分支。规则出处：05-action §5.8.1 规则 4。 */
class HttpToolAdapterTest {

    private MockWebServer server;
    private final HttpToolAdapter adapter = new HttpToolAdapter();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ToolDefinition tool(int timeout) {
        return new ToolDefinition(ToolId.of("t1"), "t1", "说明", new ToolSchema("{}"), null,
                new ToolSource(SourceType.REST, server.url("/tool").toString()), SideEffectLevel.READ_ONLY,
                timeout, ToolStatus.ACTIVE);
    }

    private static Map<String, Object> args() {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("text", "中文");
        args.put("n", 3);
        args.put("flag", true);
        args.put("none", null);
        args.put("list", java.util.Arrays.asList(1, 2));
        return args;
    }

    @Test
    void 成功时返回响应体并原样送出参数() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("ok"));

        String body = adapter.invoke(tool(2000), args());

        assertEquals("ok", body);
        RecordedRequest request = server.takeRequest();
        String sent = request.getBody().readUtf8();
        assertTrue(sent.contains("\"text\":\"中文\""), sent);
        assertTrue(sent.contains("\"n\":3"), sent);
        assertTrue(sent.contains("\"flag\":true"), sent);
        assertTrue(sent.contains("\"none\":null"), sent);
    }

    @Test
    void 错误状态码被包装成结果() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        String body = adapter.invoke(tool(2000), args());

        assertTrue(body.contains("HTTP 500"), body);
        assertTrue(body.contains("boom"), body);
    }

    @Test
    void 连不上时抛异常() throws IOException {
        server.shutdown();
        assertThrows(RuntimeException.class, () -> adapter.invoke(tool(500), args()));
    }
}
