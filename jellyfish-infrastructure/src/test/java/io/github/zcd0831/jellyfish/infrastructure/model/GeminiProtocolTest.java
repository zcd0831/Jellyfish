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

/** Gemini 映射：systemInstruction、contents 轮次、functionCall/functionResponse 按 name 配对。 */
class GeminiProtocolTest {

    private final GeminiProtocol protocol = new GeminiProtocol();
    private final ObjectMapper mapper = new ObjectMapper();

    private static ModelInvocationRequest request(List<ChatMessage> messages) {
        List<ToolSchemaSnapshot> tools = Arrays.asList(new ToolSchemaSnapshot("read_file", "读文件",
                "{\"$schema\":\"https://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                        + "\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\"}}}",
                "内容"));
        return new ModelInvocationRequest(ModelId.of("m1"), "你是助手", messages, tools, 100, null);
    }

    @Test
    void 请求把系统提示词放systemInstruction并用name配对工具结果() throws Exception {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.of(RecordRole.USER, "读文件"),
                ChatMessage.assistant("我读一下", Arrays.asList(new ToolCall("c1", "read_file", "{\"path\":\"a\"}"))),
                ChatMessage.toolResult("c1", "A"));

        JsonNode root = mapper.readTree(protocol.buildRequest(request(messages), "gemini-2.5-pro", 2048, true));

        assertEquals("你是助手", root.path("systemInstruction").path("parts").path(0).path("text").asText());
        assertEquals("user", root.path("contents").path(0).path("role").asText());
        JsonNode model = root.path("contents").path(1);
        assertEquals("model", model.path("role").asText());
        assertEquals("我读一下", model.path("parts").path(0).path("text").asText());
        assertEquals("read_file", model.path("parts").path(1).path("functionCall").path("name").asText());
        JsonNode result = root.path("contents").path(2);
        assertEquals("user", result.path("role").asText());
        assertEquals("read_file", result.path("parts").path(0).path("functionResponse").path("name").asText(),
                "functionResponse 按 name 配对，不是 callId");
        assertEquals("A", result.path("parts").path(0).path("functionResponse").path("response").path("result").asText());
        assertEquals(2048, root.path("generationConfig").path("maxOutputTokens").asInt());
        JsonNode parameters = root.path("tools").path(0).path("functionDeclarations").path(0).path("parameters");
        assertFalse(parameters.has("$schema"), "Gemini 的 JSON Schema 子集不支持 $schema");
        assertFalse(parameters.has("additionalProperties"));
        assertTrue(parameters.has("properties"));
    }

    @Test
    void 首轮是model时补一条user转场() throws Exception {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.assistant("我先看看", Arrays.asList(new ToolCall("c1", "read_file", "{}"))),
                ChatMessage.toolResult("c1", "A"));

        JsonNode root = mapper.readTree(protocol.buildRequest(request(messages), "gemini", 0, false));

        assertEquals("user", root.path("contents").path(0).path("role").asText());
        assertEquals("model", root.path("contents").path(1).path("role").asText());
        assertFalse(root.has("generationConfig"), "未配置输出上限时不写 generationConfig");
    }

    @Test
    void 解析文本工具调用与用量() {
        String body = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
                + "{\"text\":\"看一下\"},{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"a\"}}}]},"
                + "\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":7}}";

        ModelInvocationResult result = protocol.parseResponse(body, ModelId.of("m1"));

        assertEquals("看一下", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertTrue(result.toolCalls().get(0).callId().startsWith("call_"));
        assertTrue(result.toolCalls().get(0).argumentsJson().contains("\"a\""));
        assertEquals(11L, result.usage().inputTokens());
        assertEquals(7L, result.usage().outputTokens());
    }

    @Test
    void 流式累加文本与functionCall并去重() {
        ChatProtocolAdapter.StreamAccumulator accumulator = protocol.newStreamAccumulator();

        accumulator.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"先\"}]}}]}");
        accumulator.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"看看\"}]}}]}");
        String call = "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"read_file\","
                + "\"args\":{\"path\":\"a\"}}}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":5}}";
        accumulator.accept(call);
        accumulator.accept(call);
        boolean more = accumulator.accept("[DONE]");

        assertFalse(more);
        ModelInvocationResult result = accumulator.result(ModelId.of("m1"));
        assertEquals("先看看", result.text());
        assertEquals(1, result.toolCalls().size(), "同一调用重复到达时只算一次");
        assertEquals(9L, result.usage().inputTokens());
        assertEquals(5L, result.usage().outputTokens());
    }

    @Test
    void 端点按流式与非流式区分且凭据走请求头() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
                protocol.url("https://generativelanguage.googleapis.com", "gemini-2.5-pro", "k", false));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
                protocol.url("https://generativelanguage.googleapis.com", "gemini-2.5-pro", "k", true));
        Map<String, String> headers = protocol.headers("secret");
        assertEquals("secret", headers.get("x-goog-api-key"));
        assertFalse(headers.containsKey("Authorization"));
    }
}
