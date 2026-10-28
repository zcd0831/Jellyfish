package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯映射：不联网、不打桩 HTTP。
 */
class ChatCompletionsProtocolTest {

    private final ChatCompletionsProtocol protocol = new ChatCompletionsProtocol();

    private static ModelInvocationRequest request(List<ChatMessage> messages) {
        List<ToolSchemaSnapshot> tools = Arrays.asList(
                new ToolSchemaSnapshot("read_file", "读文件",
                        "{\"type\":\"object\",\"required\":[\"path\"]}", "文件内容"),
                new ToolSchemaSnapshot("ask_user", "问用户", "{\"type\":\"object\"}", "用户回答"));
        return new ModelInvocationRequest(ModelId.of("m1"), "你是助手", messages, tools, 100, null);
    }

    @Test
    void 系统提示词成为首条消息且工具清单原样透传() {
        String body = protocol.buildRequest(request(new ArrayList<ChatMessage>()), "gpt-4o", false);

        assertTrue(body.contains("\"role\":\"system\""), body);
        assertTrue(body.contains("\"content\":\"你是助手\""), body);
        assertTrue(body.contains("\"tools\""), body);
        assertTrue(body.contains("\"name\":\"read_file\""), body);
        assertTrue(body.contains("\"required\":[\"path\"]"), body);
        assertTrue(body.contains("\"model\":\"gpt-4o\""), body);
        assertTrue(body.contains("\"stream\":false"), body);
    }

    @Test
    void 工具调用与工具结果按协议角色映射() {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.of(RecordRole.USER, "读一下 /tmp/a"),
                ChatMessage.assistant(null, Arrays.asList(new ToolCall("call_1", "read_file", "{\"path\":\"/tmp/a\"}"))),
                ChatMessage.toolResult("call_1", "内容"));

        String body = protocol.buildRequest(request(messages), "gpt-4o", true);

        assertTrue(body.contains("\"tool_calls\""), body);
        assertTrue(body.contains("\"id\":\"call_1\""), body);
        assertTrue(body.contains("\"role\":\"tool\""), body);
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""), body);
        assertTrue(body.contains("\"stream\":true"), body);
    }

    @Test
    void 解析非流式响应() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"好的\","
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"/x\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}";

        ModelInvocationResult result = protocol.parseResponse(body, ModelId.of("m1"));

        assertEquals("好的", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertTrue(result.toolCalls().get(0).argumentsJson().contains("/x"));
        assertEquals(11L, result.usage().inputTokens());
        assertEquals(7L, result.usage().outputTokens());
    }

    @Test
    void 文本与多个工具调用落在同一条assistant消息里() {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.of(RecordRole.USER, "读两个文件"),
                ChatMessage.assistant("我先读一下", Arrays.asList(
                        new ToolCall("c1", "read_file", "{\"path\":\"a\"}"),
                        new ToolCall("c2", "read_file", "{\"path\":\"b\"}"))),
                ChatMessage.toolResult("c1", "A"),
                ChatMessage.toolResult("c2", "B"));

        String body = protocol.buildRequest(request(messages), "deepseek-flash", 4096, true);

        com.fasterxml.jackson.databind.JsonNode root = readTree(body);
        com.fasterxml.jackson.databind.JsonNode assistant = root.path("messages").path(2);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("我先读一下", assistant.path("content").asText());
        assertEquals(2, assistant.path("tool_calls").size(), "两个调用必须在同一条 assistant 消息里");
        assertEquals("tool", root.path("messages").path(3).path("role").asText());
        assertEquals("c1", root.path("messages").path(3).path("tool_call_id").asText());
        assertEquals("c2", root.path("messages").path(4).path("tool_call_id").asText());
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 带reasoning的工具消息原样回传reasoning_content() {
        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.assistant(null, Arrays.asList(new ToolCall("call_1", "read_file", "{}")), "我想先看目录"),
                ChatMessage.toolResult("call_1", "内容"));

        String body = protocol.buildRequest(request(messages), "deepseek-flash", true);

        assertTrue(body.contains("\"reasoning_content\":\"我想先看目录\""), body);
    }

    @Test
    void 解析非流式响应里的reasoning_content() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"好的\","
                + "\"reasoning_content\":\"先想想\","
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}}]}";

        ModelInvocationResult result = protocol.parseResponse(body, ModelId.of("m1"));

        assertEquals("先想想", result.reasoning());
    }

    @Test
    void 流式累加reasoning_content() {
        ChatCompletionsProtocol.StreamAccumulator accumulator = protocol.new StreamAccumulator();

        accumulator.accept("{\"choices\":[{\"delta\":{\"reasoning_content\":\"先\"}}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"reasoning_content\":\"想想\"}}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]}}]}");

        assertEquals("先想想", accumulator.result(ModelId.of("m1")).reasoning());
    }

    @Test
    void 流式累加文本与分段到达的工具参数() {
        ChatCompletionsProtocol.StreamAccumulator accumulator = protocol.new StreamAccumulator();

        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"pa\"}}]}}]}");
        accumulator.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"th\\\":\\\"/x\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
        boolean more = accumulator.accept("[DONE]");

        assertTrue(!more);
        ModelInvocationResult result = accumulator.result(ModelId.of("m1"));
        assertEquals("你好", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertEquals("{\"path\":\"/x\"}", result.toolCalls().get(0).argumentsJson());
        assertEquals(3L, result.usage().inputTokens());
    }
}
