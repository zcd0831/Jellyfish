package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.RecordingListener;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.StubInterceptor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.directExecutor;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.errorStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.json;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.jsonStub;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.provider;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.requestBody;
import static zcd.jellyfish.infra.llm.LlmClientTestSupport.sseStub;

/**
 * {@link ClaudeLlmClient} 的单元测试：重点覆盖工具结果合并、原生字段映射与流式事件。
 *
 * @author zcd
 */
class ClaudeLlmClientTest {

    /** 基准地址。 */
    private static final String BASE_URL = "https://api.anthropic.com";

    /** 覆盖文本、思考过程与工具调用三种 content block 的流式响应。 */
    private static final String STREAM_SSE =
            "event: message_start\n"
                    + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n"
                    + "event: content_block_start\n"
                    + "data: {\"type\":\"content_block_start\",\"index\":0,"
                    + "\"content_block\":{\"type\":\"text\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":0,"
                    + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"why\"}}\n\n"
                    + "event: content_block_stop\n"
                    + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                    + "event: content_block_start\n"
                    + "data: {\"type\":\"content_block_start\",\"index\":1,"
                    + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"search\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                    + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"abc\"}}\n\n"
                    + "event: content_block_delta\n"
                    + "data: {\"type\":\"content_block_delta\",\"index\":1,"
                    + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"def\"}}\n\n"
                    + "event: content_block_stop\n"
                    + "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n"
                    + "event: message_delta\n"
                    + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},"
                    + "\"usage\":{\"output_tokens\":5}}\n\n"
                    + "event: message_stop\n"
                    + "data: {\"type\":\"message_stop\"}\n\n";

    @Test
    void chat_should_merge_consecutive_tool_results_into_single_user_message() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.assistant(null, Arrays.asList(
                        new LlmToolCall(0, "t1", "toolA", "{}"),
                        new LlmToolCall(1, "t2", "toolB", "{}"))))
                .message(LlmMessage.tool("t1", "toolA", "r1"))
                .message(LlmMessage.tool("t2", "toolB", "r2"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode messages = json(requestBody(stub.lastRequest())).path("messages");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals(2, messages.get(1).path("content").size());
        assertEquals("user", messages.get(2).path("role").asText());
        assertEquals(2, messages.get(2).path("content").size());
        assertEquals("tool_result", messages.get(2).path("content").get(0).path("type").asText());
        assertEquals("t2", messages.get(2).path("content").get(1).path("tool_use_id").asText());
    }

    @Test
    void chat_should_send_anthropic_fields_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .temperature(0.5)
                .topP(0.9)
                .maxTokens(100)
                .stop(Arrays.asList("stop"))
                .tools(Collections.singletonList(
                        new LlmTool("search", "desc", Collections.singletonMap("q", "string"),
                                Collections.singletonList("q"))))
                .toolChoice("search")
                .build();

        // When
        client.chat(request);

        // Then
        Request httpRequest = stub.lastRequest();
        assertTrue(httpRequest.url().toString().endsWith("/v1/messages"));
        assertEquals("key", httpRequest.header("x-api-key"));
        assertEquals("2023-06-01", httpRequest.header("anthropic-version"));
        JsonNode body = json(requestBody(httpRequest));
        // system 现在是块数组而不是字符串：只有内容块才能挂 cache_control（详见请求体构造处的说明）
        assertEquals("sys", body.path("system").get(0).path("text").asText());
        assertEquals(100, body.path("max_tokens").asInt());
        assertEquals(0.5, body.path("temperature").asDouble());
        assertEquals(0.9, body.path("top_p").asDouble());
        assertEquals("stop", body.path("stop_sequences").get(0).asText());
        assertEquals("search", body.path("tools").get(0).path("name").asText());
        assertTrue(body.path("tools").get(0).has("input_schema"));
        assertEquals("tool", body.path("tool_choice").path("type").asText());
        assertEquals("search", body.path("tool_choice").path("name").asText());
    }

    @Test
    void chat_should_default_max_tokens_when_not_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build());

        // Then
        assertEquals(4096, json(requestBody(stub.lastRequest())).path("max_tokens").asInt());
    }

    @Test
    void chat_should_collect_system_messages_into_system_field() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);
        LlmRequest request = LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("prompt")
                .message(LlmMessage.system("extra"))
                .message(LlmMessage.user("hi"))
                .build();

        // When
        client.chat(request);

        // Then
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("prompt\nextra", body.path("system").get(0).path("text").asText());
        assertEquals(1, body.path("messages").size());
    }

    @Test
    void chat_should_keepTrailingSystemMessageInPlace_whenItFollowsUserMessage() throws IOException {
        // Given：会话中途的 operator 指令（模式切换、注入状态）跟在 user 消息之后、且是最后一条——
        // 这是官方允许的放置，而且是「不能伪造」的通道
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("稳定前缀")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.system("精简模式"))
                .build());

        // Then：它必须留在 messages 里。上提到顶层 system 等于改了请求的第 0 个 token，
        // 整段已经缓存的前缀随之作废——那正是本条通道存在的理由
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("稳定前缀", body.path("system").get(0).path("text").asText());
        JsonNode messages = body.path("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(1).path("role").asText());
        assertEquals("精简模式", messages.get(1).path("content").get(0).path("text").asText());
    }

    @Test
    void chat_should_hoistSystemMessage_when_itIsNotTheTrailingOne() throws IOException {
        // Given：中间位置的 system 消息（官方对放置另有要求，内核保守地一律上提）
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.system("早先的指令"))
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.assistant("ok"))
                .build());

        // Then：上提（代价是缓存失效），而不是构造一个自己推不出合法性的放置
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("早先的指令", body.path("system").get(0).path("text").asText());
        assertEquals(2, body.path("messages").size());
    }

    @Test
    void chat_should_hoistTrailingSystemMessage_when_itDoesNotFollowUserMessage() throws IOException {
        // Given：跟在 assistant 之后
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.assistant("ok"))
                .message(LlmMessage.system("指令"))
                .build());

        // Then
        assertEquals("指令", json(requestBody(stub.lastRequest())).path("system").get(0).path("text").asText());
    }

    @Test
    void chat_should_hoistSystemMessage_when_itIsTheOnlyMessage() throws IOException {
        // Given：messages[0] 是官方明确不允许放 system 的位置（那正是顶层 system 的用途）
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.system("唯一的指令")).build());

        // Then
        assertEquals("唯一的指令", json(requestBody(stub.lastRequest())).path("system").get(0).path("text").asText());
    }

    @Test
    void chat_should_notSendTrailingSystemMessage_twice() throws IOException {
        // Given：留在原位与上提到顶层是同一个决定的两面——两处都发就成了同一段话说两遍
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.system("只应出现一次"))
                .build());

        // Then
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertFalse(body.path("system").asText().contains("只应出现一次"));
        assertTrue(body.path("messages").toString().contains("只应出现一次"));
    }

    void chat_should_map_required_tool_choice_to_any_when_configured() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(toolChoiceRequest("required"));

        // Then
        assertEquals("any", json(requestBody(stub.lastRequest()))
                .path("tool_choice").path("type").asText());
    }

    @Test
    void chat_should_map_none_tool_choice_to_type_none() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(toolChoiceRequest("none"));

        // Then：必须显式下发 {type: none}，而不是「不下发」——后者等于默认 auto，
        // 带着工具却不禁用，模型很可能去调工具。压缩的 cache-safe fork 靠这个取值保证只出文本
        assertEquals("none",
                json(requestBody(stub.lastRequest())).path("tool_choice").path("type").asText());
    }

    @Test
    void chat_should_omit_tool_choice_when_unset() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When：未指定策略时不下发该字段，走厂商自己的默认
        client.chat(toolChoiceRequest(null));

        // Then
        assertFalse(json(requestBody(stub.lastRequest())).has("tool_choice"));
    }

    @Test
    void chat_should_parse_text_thinking_and_tool_use_when_response_has_them() {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"thinking\",\"thinking\":\"why\"},"
                + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{\"a\":1}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":3,\"output_tokens\":4}}");
        ClaudeLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi")).build());

        // Then
        assertEquals("hi", response.getContent());
        assertEquals("why", response.getThinking());
        assertEquals("tool_use", response.getFinishReason());
        assertEquals(7, response.getUsage().getTotalTokens());
        assertEquals("search", response.getToolCalls().get(0).getName());
        assertEquals("{\"a\":1}", response.getToolCalls().get(0).getArguments());
    }

    @Test
    void chat_should_throw_when_response_content_is_not_array() {
        // Given
        ClaudeLlmClient client = client(jsonStub("{\"stop_reason\":\"end_turn\"}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(
                LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build()));
    }

    @Test
    void chat_should_throw_when_request_is_null() {
        // Given
        ClaudeLlmClient client = client(jsonStub("{\"content\":[]}"));

        // When / Then
        assertThrows(JellyfishException.class, () -> client.chat(null));
    }

    @Test
    void chat_should_sendZeroMaxTokens_when_minimalOutputRequested() throws IOException {
        // Given：Anthropic 明确支持 max_tokens: 0（只做 prefill 并写缓存，不生成输出），
        // 这正是缓存保活要的形式——它要的不是内容，而是把缓存 TTL 续上
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi"))
                .minimalOutput().build());

        // Then
        assertEquals(0, json(requestBody(stub.lastRequest())).path("max_tokens").asInt());
    }

    @Test
    void chatStream_should_fallBackToOneMaxTokens_when_minimalOutputRequested() throws IOException {
        // Given：max_tokens: 0 与 stream: true 互斥（会被拒），因此流式下退回 1。
        // 流式只是传输方式、不属于被缓存的前缀，退这一步没有代价
        StubInterceptor stub = sseStub(STREAM_SSE);
        ClaudeLlmClient client = client(stub);

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi"))
                .minimalOutput().build(), new RecordingListener());

        // Then
        assertEquals(1, json(requestBody(stub.lastRequest())).path("max_tokens").asInt());
    }

    @Test
    void chatStream_should_emit_text_thinking_and_tool_call_when_stream_completes() {
        // Given
        ClaudeLlmClient client = client(sseStub(STREAM_SSE));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertEquals(Arrays.asList("Hi"), listener.texts);
        assertEquals(Arrays.asList("why"), listener.thinkings);
        assertEquals(1, listener.toolCalls.size());
        assertEquals("search", listener.toolCalls.get(0).getName());
        assertEquals("abcdef", listener.toolCalls.get(0).getArguments());
        assertEquals("Hi", listener.completed.getContent());
        assertEquals("why", listener.completed.getThinking());
        assertEquals("tool_use", listener.completed.getFinishReason());
        assertEquals(15, listener.completed.getUsage().getTotalTokens());
        assertNull(listener.error);
    }

    @Test
    void chatStream_should_notify_error_when_error_event_received() {
        // Given
        ClaudeLlmClient client = client(sseStub(
                "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"rate limited\"}}\n\n"));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
        assertTrue(listener.error.getMessage().contains("rate limited"));
        assertNull(listener.completed);
    }

    @Test
    void chatStream_should_notify_error_when_http_status_is_error() {
        // Given
        ClaudeLlmClient client = client(errorStub(429, "{\"error\":\"too many\"}"));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("hi")).build(),
                listener);

        // Then
        assertTrue(listener.error instanceof JellyfishException);
    }


    @Test
    void chat_should_mark_system_block_and_last_message_by_default() throws IOException {
        // Given：什么都不声明，用内核缺省（两个断点）
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .build());

        // Then：前端断点在最后一个 system 块上（它排在工具之后，一个标记同时护住两段），
        // 尾部断点在最后一条消息上（它随对话增长前移，使每轮只需 prefill 新增部分）
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("ephemeral", body.path("system").get(0).path("cache_control").path("type").asText());
        JsonNode lastMessage = body.path("messages").get(body.path("messages").size() - 1);
        assertEquals("ephemeral", lastMessage.path("content").get(0).path("cache_control").path("type").asText());
        assertEquals("hi", lastMessage.path("content").get(0).path("text").asText());
    }

    @Test
    void chat_should_mark_last_tool_when_system_prompt_absent() throws IOException {
        // Given：没有 system prompt，前端断点只能落在最后一个工具定义上
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .tools(Arrays.asList(new LlmTool("a", "甲", null, null), new LlmTool("b", "乙", null, null)))
                .build());

        // Then
        JsonNode tools = json(requestBody(stub.lastRequest())).path("tools");
        assertFalse(tools.get(0).has("cache_control"));
        assertEquals("ephemeral", tools.get(1).path("cache_control").path("type").asText());
    }

    @Test
    void chat_should_mark_only_system_block_when_breakpoints_is_one() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .cacheBreakpoints(Integer.valueOf(1))
                .build());

        // Then：只护住稳定前端。历史被改写时它不受影响，但那一段本来也常变。
        // 尾部不标则消息内容保持纯字符串形式——为一个断点改写结构没有意义
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertTrue(body.path("system").get(0).has("cache_control"));
        assertTrue(body.path("messages").get(0).path("content").isTextual());
    }

    @Test
    void chat_should_not_mark_anything_when_breakpoints_is_zero() throws IOException {
        // Given
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .cacheBreakpoints(Integer.valueOf(0))
                .build());

        // Then：关闭时连 system 也回到字符串形式——不留一个多余的块数组
        JsonNode body = json(requestBody(stub.lastRequest()));
        assertEquals("sys", body.path("system").asText());
        assertTrue(body.path("messages").get(0).path("content").isTextual());
    }

    @Test
    void chat_should_put_ttl_into_cache_control_when_retention_set() throws IOException {
        // Given：Anthropic 的 TTL 就是 cache_control.ttl
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .systemPrompt("sys")
                .message(LlmMessage.user("hi"))
                .cacheRetention("1h")
                .build());

        // Then
        JsonNode body = json(requestBody(stub.lastRequest()));
        JsonNode cacheControl = body.path("system").get(0).path("cache_control");
        assertEquals("ephemeral", cacheControl.path("type").asText());
        assertEquals("1h", cacheControl.path("ttl").asText());
    }

    @Test
    void chat_should_omit_ttl_when_retention_unset() throws IOException {
        // Given：Anthropic 的缺省 TTL 是 5m，内核不替调用方猜，因此不下发该字段
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").systemPrompt("sys")
                .message(LlmMessage.user("hi")).build());

        // Then
        JsonNode cacheControl = json(requestBody(stub.lastRequest()))
                .path("system").get(0).path("cache_control");
        assertFalse(cacheControl.has("ttl"));
    }

    @Test
    void chat_should_mark_last_tool_result_block_when_last_message_is_tool_results() throws IOException {
        // Given：最后一条是工具结果（它本来就已经是块数组）
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(new LlmMessage(LlmMessage.ROLE_TOOL, "r1", "call-1", null, null))
                .message(new LlmMessage(LlmMessage.ROLE_TOOL, "r2", "call-2", null, null))
                .build());

        // Then：两个结果合并进同一条 user 消息，断点落在后一个 block 上
        JsonNode messages = json(requestBody(stub.lastRequest())).path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals(2, last.path("content").size());
        assertFalse(last.path("content").get(0).has("cache_control"));
        assertTrue(last.path("content").get(1).has("cache_control"));
    }

    @Test
    void chat_should_merge_user_text_into_pending_tool_result_message() throws IOException {
        // Given：工具批次之后紧跟一条用户消息——插件经动作通道插话（DeliverAs#STEER）就是这个形状，
        // 回合被取消后补了「未执行」结果、用户又开一轮也是这个形状
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(new LlmMessage(LlmMessage.ROLE_TOOL, "r1", "call-1", null, null))
                .message(LlmMessage.user("顺便把日志也改了"))
                .build());

        // Then：文本必须并进那条装工具结果的 user 消息。另起一条会被 Anthropic 以
        // 「user / assistant 必须交替」直接拒绝，而那是一条很难归因的 400
        JsonNode messages = json(requestBody(stub.lastRequest())).path("messages");
        assertEquals(2, messages.size());
        JsonNode last = messages.get(messages.size() - 1);
        assertEquals("user", last.path("role").asText());
        assertEquals(2, last.path("content").size());
        assertEquals("tool_result", last.path("content").get(0).path("type").asText());
        assertEquals("text", last.path("content").get(1).path("type").asText());
        assertEquals("顺便把日志也改了", last.path("content").get(1).path("text").asText());
    }

    @Test
    void chat_should_keep_user_message_separate_when_no_tool_result_pending() throws IOException {
        // 没有挂起的工具结果时，用户消息照旧是独立一条：合并只针对「会产生连续 user」那一种形状
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .message(LlmMessage.user("又是我"))
                .build());

        JsonNode messages = json(requestBody(stub.lastRequest())).path("messages");
        assertEquals(2, messages.size());
        assertEquals("又是我", messages.get(1).path("content").get(0).path("text").asText());
    }

    @Test
    void chat_should_not_mark_empty_text_block_when_last_message_blank() throws IOException {
        // Given：Anthropic 不接受空文本块，为一个断点把请求弄成非法不值得
        StubInterceptor stub = jsonStub("{\"content\":[]}");
        ClaudeLlmClient client = client(stub);

        // When
        client.chat(LlmRequest.builder("claude-3-5-sonnet").message(LlmMessage.user("  ")).build());

        // Then
        assertEquals("  ", json(requestBody(stub.lastRequest())).path("messages").get(0).path("content").asText());
    }

    /**
     * 构造带工具与指定 tool_choice 的请求。
     *
     * @param toolChoice 工具选择策略
     * @return 统一请求模型
     */
    private static LlmRequest toolChoiceRequest(String toolChoice) {
        return LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi"))
                .tools(Collections.singletonList(new LlmTool("search", "desc", null, null)))
                .toolChoice(toolChoice)
                .build();
    }

    @Test
    void chat_should_normalize_input_tokens_when_cache_fields_are_reported() throws IOException {
        // Given：Anthropic 的三个输入字段是互斥划分——input_tokens 只计「新增、未命中、未建缓存」的
        // 部分，缓存命中数单独给（这个形状与 OpenAI/DeepSeek 相反）
        StubInterceptor stub = jsonStub("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                + "\"usage\":{\"input_tokens\":3,\"output_tokens\":5,"
                + "\"cache_read_input_tokens\":180000,\"cache_creation_input_tokens\":8000}}");
        ClaudeLlmClient client = client(stub);

        // When
        LlmResponse response = client.chat(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi")).build());

        // Then：总输入要把三部分加起来。不加回去的话，缓存一旦生效就会把绝大部分输入漏掉，
        // 而缓存命中恰恰是「输入很多、新增很少」的场景
        assertEquals(188003, response.getUsage().getPromptTokens());
        assertEquals(180000, response.getUsage().getCacheReadTokens());
        assertEquals(8000, response.getUsage().getCacheWriteTokens());
    }

    @Test
    void chatStream_should_normalize_input_tokens_when_cache_fields_are_reported() {
        // Given：三个输入字段只在 message_start 里给一次（流式路径有自己的一套累加）
        String sse = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3,"
                + "\"cache_read_input_tokens\":900,\"cache_creation_input_tokens\":100}}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":5}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        ClaudeLlmClient client = client(sseStub(sse));
        RecordingListener listener = new RecordingListener();

        // When
        client.chatStream(LlmRequest.builder("claude-3-5-sonnet")
                .message(LlmMessage.user("hi")).build(), listener);

        // Then：流式路径与同步路径必须算出同一个口径，否则同一个会话的命中率会随调用方式跳变
        assertEquals(1003, listener.completed.getUsage().getPromptTokens());
        assertEquals(900, listener.completed.getUsage().getCacheReadTokens());
        assertEquals(100, listener.completed.getUsage().getCacheWriteTokens());
    }

    /**
     * 构造指向离线拦截器的 Claude 客户端。
     *
     * @param stub 拦截器桩
     * @return Claude 客户端
     */
    private static ClaudeLlmClient client(StubInterceptor stub) {
        return new ClaudeLlmClient(provider("claude", "key", BASE_URL), stub.client(), directExecutor());
    }
}
