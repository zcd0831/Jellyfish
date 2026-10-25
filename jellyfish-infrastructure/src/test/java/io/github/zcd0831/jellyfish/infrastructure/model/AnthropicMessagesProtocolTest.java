package io.github.zcd0831.jellyfish.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anthropic Messages 映射：顶层 system、content blocks、tool_use/tool_result 同轮配对。 */
class AnthropicMessagesProtocolTest {

    private final AnthropicMessagesProtocol protocol = new AnthropicMessagesProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    private static ModelInvocationRequest request(List<ChatMessage> messages) {
        List<ToolSchemaSnapshot> tools = Arrays.asList(new ToolSchemaSnapshot("read_file", "读文件",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}", "内容"));
        return new ModelInvocationRequest(ModelId.of("m1"), "你是助手", messages, tools, 100, null);
    }

    @Test
    void 请求把系统提示词放顶层并把文本与tool_use放进同一条assistant消息() throws Exception {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.of(RecordRole.USER, "读两个文件"),
                ChatMessage.assistant("我先读一下", Arrays.asList(
                        new ToolCall("c1", "read_file", "{\"path\":\"a\"}"),
                        new ToolCall("c2", "read_file", "{\"path\":\"b\"}"))),
                ChatMessage.toolResult("c1", "A"),
                ChatMessage.toolResult("c2", "B"));

        JsonNode root = mapper.readTree(protocol.buildRequest(request(messages), "claude", 4096, true));

        assertTrue(root.path("stream").asBoolean());
        assertEquals(4096, root.path("max_tokens").asInt(), "Anthropic 必填 max_tokens");
        assertEquals("你是助手", root.path("system").asText());
        JsonNode assistant = root.path("messages").path(1);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("text", assistant.path("content").path(0).path("type").asText());
        assertEquals("tool_use", assistant.path("content").path(1).path("type").asText());
        assertEquals(3, assistant.path("content").size(), "一条文本 + 两个 tool_use 在同一条消息里");
        assertEquals("c2", assistant.path("content").path(2).path("id").asText());
        JsonNode user = root.path("messages").path(2);
        assertEquals("user", user.path("role").asText());
        assertEquals("tool_result", user.path("content").path(0).path("type").asText());
        assertEquals("c1", user.path("content").path(0).path("tool_use_id").asText());
        assertEquals("c2", user.path("content").path(1).path("tool_use_id").asText());
        assertEquals("read_file", root.path("tools").path(0).path("name").asText());
        assertTrue(root.path("tools").path(0).has("input_schema"));
    }

    @Test
    void 首条是assistant时补一条user转场以满足交替要求() throws Exception {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.assistant("我先看看", Arrays.asList(new ToolCall("c1", "read_file", "{}"))),
                ChatMessage.toolResult("c1", "A"));

        JsonNode root = mapper.readTree(protocol.buildRequest(request(messages), "claude", 0, false));

        assertEquals("user", root.path("messages").path(0).path("role").asText());
        assertEquals("assistant", root.path("messages").path(1).path("role").asText());
        assertEquals("user", root.path("messages").path(2).path("role").asText());
        assertTrue(root.path("max_tokens").asInt() > 0, "未配置输出上限时给一个安全默认值");
    }

    @Test
    void 解析文本工具调用与用量() {
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"看一下\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{\"path\":\"a\"}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}";

        ModelInvocationResult result = protocol.parseResponse(body, ModelId.of("m1"));

        assertEquals("看一下", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("toolu_1", result.toolCalls().get(0).callId());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertTrue(result.toolCalls().get(0).argumentsJson().contains("\"a\""));
        assertEquals(11L, result.usage().inputTokens());
        assertEquals(7L, result.usage().outputTokens());
    }

    @Test
    void 流式累加文本与input_json_delta() {
        ChatProtocolAdapter.StreamAccumulator accumulator = protocol.newStreamAccumulator();

        accumulator.accept("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9,\"output_tokens\":0}}}");
        accumulator.accept("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"先\"}}");
        accumulator.accept("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"看看\"}}");
        accumulator.accept("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"toolu_1\",\"name\":\"read_file\"}}");
        accumulator.accept("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"path\\\":\"}}");
        accumulator.accept("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"\\\"a\\\"}\"}}");
        accumulator.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":12}}");
        boolean more = accumulator.accept("{\"type\":\"message_stop\"}");

        assertFalse(more);
        ModelInvocationResult result = accumulator.result(ModelId.of("m1"));
        assertEquals("先看看", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("{\"path\":\"a\"}", result.toolCalls().get(0).argumentsJson());
        assertEquals(9L, result.usage().inputTokens());
        assertEquals(12L, result.usage().outputTokens());
    }

    @Test
    void 签名thinking原样带回并排在内容块之前() throws Exception {
        String body = "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"先想想\","
                + "\"signature\":\"sig-1\"},{\"type\":\"text\",\"text\":\"看一下\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        ModelInvocationResult parsed = protocol.parseResponse(body, ModelId.of("m1"));
        assertTrue(parsed.reasoning().contains("sig-1"), "签名必须留在私有状态里");

        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.of(RecordRole.USER, "读文件"),
                ChatMessage.assistant("看一下", Arrays.asList(new ToolCall("toolu_1", "read_file", "{}")),
                        parsed.reasoning()),
                ChatMessage.toolResult("toolu_1", "A"));

        JsonNode assistant = mapper.readTree(protocol.buildRequest(request(messages), "claude", 0, false))
                .path("messages").path(1);

        assertEquals("thinking", assistant.path("content").path(0).path("type").asText(),
                "thinking 必须排在 text/tool_use 之前");
        assertEquals("sig-1", assistant.path("content").path(0).path("signature").asText());
        assertEquals("text", assistant.path("content").path(1).path("type").asText());
    }

    @Test
    void 流式累加签名thinking() {
        ChatProtocolAdapter.StreamAccumulator accumulator = protocol.newStreamAccumulator();

        accumulator.accept("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        accumulator.accept("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先想\"}}");
        accumulator.accept("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}");
        accumulator.accept("{\"type\":\"content_block_start\",\"index\":1,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"好\"}}");

        ModelInvocationResult result = accumulator.result(ModelId.of("m1"));

        assertEquals("好", result.text());
        assertTrue(result.reasoning().contains("先想"));
        assertTrue(result.reasoning().contains("sig"));
    }

    @Test
    void 端点与鉴权头按Anthropic规范() {
        assertEquals("https://api.anthropic.com/v1/messages",
                protocol.url("https://api.anthropic.com", "claude", "k", false));
        Map<String, String> headers = protocol.headers("secret");
        assertEquals("secret", headers.get("x-api-key"));
        assertEquals(AnthropicMessagesProtocol.API_VERSION, headers.get("anthropic-version"));
        assertFalse(headers.containsKey("Authorization"));
    }
}
