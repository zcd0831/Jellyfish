package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.support.LlmClients;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Anthropic Claude 原生实现（Messages API）。
 *
 * @author zcd
 */
public class ClaudeLlmClient extends AbstractHttpLlmClient {

    /** Anthropic 官方默认 baseUrl。 */
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** Anthropic API 版本号。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 请求未指定 max_tokens 时使用的默认值，Anthropic 要求该字段必填。 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /**
     * 构造 Claude 客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    public ClaudeLlmClient(Provider provider, OkHttpClient httpClient, ExecutorService streamExecutor) {
        super(provider, httpClient, streamExecutor);
    }

    /**
     * 解析实际使用的 baseUrl。
     *
     * @return 去掉尾部斜杠的 baseUrl
     */
    private String baseUrl() {
        return LlmClients.resolveBaseUrl(provider, DEFAULT_BASE_URL);
    }

    /**
     * 构造 Messages 接口地址。
     *
     * @return Messages 完整地址
     */
    private String messagesUrl() {
        return LlmClients.appendVersion(baseUrl(), "v1", "/messages");
    }

    /**
     * 构造带 x-api-key 与 anthropic-version 头的请求构建器。
     *
     * @return 已带鉴权头的请求构建器
     */
    private Request.Builder authorizedRequest() {
        return jsonRequest(messagesUrl())
                .header("x-api-key", LlmClients.requireApiKey(provider))
                .header("anthropic-version", ANTHROPIC_VERSION);
    }

    /**
     * 同步（非流式）对话调用。
     *
     * @param request 统一请求模型
     * @return 统一返回结果
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        requireRequest(request);
        Request httpRequest = authorizedRequest()
                .post(jsonBody(buildRequestBody(request, false)))
                .build();
        JsonNode root = executeForJson(httpRequest, JsonNode.class, "chat request");
        return parseResponse(root);
    }

    /**
     * 流式对话调用。
     *
     * @param request  统一请求模型
     * @param listener 流式响应监听器
     * @return 可用于取消本次请求的句柄
     */
    @Override
    public LlmStreamHandle chatStream(LlmRequest request, LlmStreamListener listener) {
        requireRequest(request);
        Request httpRequest = authorizedRequest()
                .header("Accept", "text/event-stream")
                .post(jsonBody(buildRequestBody(request, true)))
                .build();
        return startStream(httpRequest, listener, new ClaudeStreamDecoder());
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /**
     * 构造 Messages 请求体。
     *
     * @param request 统一请求模型，不可为空
     * @param stream  是否为流式请求
     * @return 可直接序列化为 JSON 的请求体
     * @throws JellyfishException 请求为 {@code null} 时抛出
     */
    private Map<String, Object> buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens() != null && request.getMaxTokens() > 0
                ? request.getMaxTokens()
                : DEFAULT_MAX_TOKENS);
        if (stream) {
            body.put("stream", true);
        }
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (!request.getStop().isEmpty()) {
            body.put("stop_sequences", request.getStop());
        }
        String system = collectSystemPrompt(request);
        if (LlmClients.isNotBlank(system)) {
            body.put("system", system);
        }
        body.put("messages", buildMessages(request));
        if (request.hasTools()) {
            body.put("tools", buildTools(request.getTools()));
            Map<String, Object> toolChoice = buildToolChoice(request.getToolChoice());
            if (!toolChoice.isEmpty()) {
                body.put("tool_choice", toolChoice);
            }
        }
        return body;
    }

    /**
     * 把统一消息模型转换为 Anthropic messages 数组。
     * <p>
     * Anthropic 要求 user / assistant 角色交替出现，因此连续的工具结果会被合并进同一条 user 消息，
     * 作为多个 {@code tool_result} block 下发。
     *
     * @param request 统一请求模型
     * @return Anthropic messages 数组
     */
    private static List<Map<String, Object>> buildMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> pendingToolResults = new ArrayList<>();
        for (LlmMessage message : request.getMessages()) {
            String role = message.getRole();
            if (LlmMessage.ROLE_SYSTEM.equals(role)) {
                continue;
            }
            if (LlmMessage.ROLE_TOOL.equals(role)) {
                // 同一轮的多个工具结果必须合并进同一条 user 消息，否则连续 user 消息会被 Anthropic 拒绝
                pendingToolResults.add(toolResultBlock(message));
                continue;
            }
            flushToolResults(messages, pendingToolResults);
            if (LlmMessage.ROLE_ASSISTANT.equals(role) && message.hasToolCalls()) {
                messages.add(assistantToolUseMessage(message));
            } else {
                messages.add(textMessage(role, LlmClients.nullToEmpty(message.getContent())));
            }
        }
        flushToolResults(messages, pendingToolResults);
        return messages;
    }

    /**
     * 将缓冲区中已累计的工具结果落盘为一条 user 消息。
     *
     * @param messages 已构建的消息列表
     * @param pendingToolResults 待落盘的工具结果 block
     */
    private static void flushToolResults(List<Map<String, Object>> messages, List<Map<String, Object>> pendingToolResults) {
        if (pendingToolResults.isEmpty()) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", LlmMessage.ROLE_USER);
        result.put("content", new ArrayList<>(pendingToolResults));
        messages.add(result);
        pendingToolResults.clear();
    }

    /**
     * 构造一条纯文本消息。
     *
     * @param role    角色
     * @param content 文本内容
     * @return Anthropic 消息结构
     */
    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 构造一个 tool_result content block。
     *
     * @param message 工具结果消息
     * @return tool_result block
     */
    private static Map<String, Object> toolResultBlock(LlmMessage message) {
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", message.getToolCallId());
        toolResult.put("content", LlmClients.nullToEmpty(message.getContent()));
        return toolResult;
    }

    /**
     * 把携带工具调用的模型回复转换为 content block 数组（text + tool_use）。
     *
     * @param message 模型回复消息
     * @return Anthropic assistant 消息结构
     */
    private static Map<String, Object> assistantToolUseMessage(LlmMessage message) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (LlmClients.isNotBlank(message.getContent())) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", message.getContent());
            content.add(text);
        }
        for (LlmToolCall toolCall : message.getToolCalls()) {
            Map<String, Object> toolUse = new LinkedHashMap<>();
            toolUse.put("type", "tool_use");
            toolUse.put("id", toolCall.getId());
            toolUse.put("name", toolCall.getName());
            toolUse.put("input", LlmClients.parseArguments(toolCall.getArguments()));
            content.add(toolUse);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", LlmMessage.ROLE_ASSISTANT);
        result.put("content", content);
        return result;
    }

    /**
     * 把统一工具定义转换为 Anthropic tools 数组。
     *
     * @param tools 工具定义
     * @return Anthropic tools 数组
     */
    private static List<Map<String, Object>> buildTools(List<LlmTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>(tools.size());
        for (LlmTool tool : tools) {
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", tool.getParameters());
            if (!tool.getRequired().isEmpty()) {
                inputSchema.put("required", tool.getRequired());
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.getName());
            entry.put("description", tool.getDescription());
            entry.put("input_schema", inputSchema);
            result.add(entry);
        }
        return result;
    }

    /**
     * 把统一工具选择策略转换为 Anthropic 的 tool_choice 结构。
     * <p>
     * {@code none} 表示不下发该字段；{@code required}/{@code any} 映射为 {@code any}；
     * 其余非关键字取值视为具体工具名（保留原始大小写，仅去除首尾空白）。
     *
     * @param toolChoice 统一工具选择策略
     * @return Anthropic tool_choice 结构，无需下发时返回空 Map
     */
    private static Map<String, Object> buildToolChoice(String toolChoice) {
        String choice = LlmClients.normalizeToolChoice(toolChoice);
        if (choice.isEmpty() || "none".equals(choice)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if ("required".equals(choice) || "any".equals(choice)) {
            result.put("type", "any");
        } else if ("auto".equals(choice)) {
            result.put("type", "auto");
        } else {
            result.put("type", "tool");
            result.put("name", toolChoice.trim());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 非流式响应
    // ------------------------------------------------------------------

    /**
     * 解析非流式响应，按 content block 类型分别归入文本、思考过程与工具调用。
     *
     * @param root 响应 JSON 根节点
     * @return 统一返回结果
     * @throws JellyfishException 响应中没有 content 数组时抛出
     */
    private LlmResponse parseResponse(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw new JellyfishException("response has no content from provider: " + provider.getName());
        }
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<LlmToolCall> toolCalls = new ArrayList<>();
        int index = 0;
        for (JsonNode block : content) {
            String type = LlmClients.textOrNull(block, "type");
            if ("tool_use".equals(type)) {
                toolCalls.add(new LlmToolCall(index, LlmClients.textOrNull(block, "id"),
                        LlmClients.textOrNull(block, "name"),
                        ObjectMapperWrapper.writeValueAsString(block.path("input"))));
                index++;
            } else if ("thinking".equals(type)) {
                append(thinking, LlmClients.textOrNull(block, "thinking"));
            } else {
                append(text, LlmClients.textOrNull(block, "text"));
            }
        }
        return new LlmResponse(LlmClients.nullableString(text), LlmClients.nullableString(thinking), toolCalls,
                parseUsage(root.path("usage")), LlmClients.textOrNull(root, "stop_reason"));
    }

    /**
     * 追加非空字符串到缓冲区。
     *
     * @param builder 目标缓冲区
     * @param value   待追加的字符串，可为 {@code null}
     */
    private static void append(StringBuilder builder, String value) {
        if (value != null) {
            builder.append(value);
        }
    }

    /**
     * 解析 usage 节点。输入与输出 token 均为 0 时视为厂商未返回。
     *
     * @param usage usage JSON 节点
     * @return token 使用量，厂商未返回时为 {@code null}
     */
    private static LlmUsage parseUsage(JsonNode usage) {
        return parseUsage(usage, "input_tokens", "output_tokens", null);
    }

    // ------------------------------------------------------------------
    // 流式响应
    // ------------------------------------------------------------------

    /**
     * Claude Messages API 的流式解码器，按 SSE 事件类型分派处理。
     *
     * @author zcd
     */
    private final class ClaudeStreamDecoder implements StreamDecoder {

        /** 累计的文本回复。 */
        private final StringBuilder content = new StringBuilder();

        /** 累计的思考过程。 */
        private final StringBuilder thinking = new StringBuilder();

        /** 进行中的工具调用，key 为 content block index。 */
        private final Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();

        /** 参数已完整、可输出的工具调用。 */
        private final List<LlmToolCall> completedToolCalls = new ArrayList<>();

        /** 输入 token 数。 */
        private int inputTokens;

        /** 输出 token 数。 */
        private int outputTokens;

        /** 结束原因。 */
        private String stopReason;

        /**
         * 处理一个 Claude SSE 事件。
         *
         * @param event    SSE 事件名，Claude 的事件类型在 data 的 type 字段中
         * @param data     事件 JSON
         * @param listener 流式响应监听器
         * @return {@code true} 表示收到 message_stop，可以停止读取
         * @throws JellyfishException 收到 error 事件时抛出
         */
        @Override
        public boolean onData(String event, String data, LlmStreamListener listener) {
            JsonNode root = ObjectMapperWrapper.readValue(data, JsonNode.class);
            String type = LlmClients.textOrNull(root, "type");
            if (type == null) {
                return false;
            }
            switch (type) {
                case "message_start":
                    inputTokens = root.path("message").path("usage").path("input_tokens").asInt(inputTokens);
                    break;
                case "content_block_start":
                    handleBlockStart(root);
                    break;
                case "content_block_delta":
                    handleBlockDelta(root, listener);
                    break;
                case "content_block_stop":
                    handleBlockStop(root, listener);
                    break;
                case "message_delta":
                    stopReason = LlmClients.firstNonBlank(
                            LlmClients.textOrNull(root.path("delta"), "stop_reason"), stopReason);
                    outputTokens = root.path("usage").path("output_tokens").asInt(outputTokens);
                    break;
                case "message_stop":
                    return true;
                case "error":
                    throw new JellyfishException("stream error from provider: " + provider.getName()
                            + ": " + root.path("error").path("message").asText("unknown"));
                default:
                    // ping 及未知事件忽略
                    break;
            }
            return false;
        }

        /**
         * 处理 content block 开始事件，工具调用块在此登记 id 与 name。
         *
         * @param root 事件 JSON 根节点
         */
        private void handleBlockStart(JsonNode root) {
            int index = root.path("index").asInt(0);
            JsonNode block = root.path("content_block");
            if ("tool_use".equals(LlmClients.textOrNull(block, "type"))) {
                ToolCallBuilder builder = new ToolCallBuilder(index,
                        LlmClients.textOrNull(block, "id"), LlmClients.textOrNull(block, "name"));
                toolCalls.put(index, builder);
            }
        }

        /**
         * 处理 content block 增量事件，按 delta 类型分派到文本、思考过程或工具参数。
         *
         * @param root     事件 JSON 根节点
         * @param listener 流式响应监听器
         */
        private void handleBlockDelta(JsonNode root, LlmStreamListener listener) {
            int index = root.path("index").asInt(0);
            JsonNode delta = root.path("delta");
            String deltaType = LlmClients.textOrNull(delta, "type");
            if (deltaType == null) {
                return;
            }
            switch (deltaType) {
                case "text_delta":
                    String text = LlmClients.textOrNull(delta, "text");
                    if (text != null && !text.isEmpty()) {
                        content.append(text);
                        listener.onText(text);
                    }
                    break;
                case "thinking_delta":
                    String think = LlmClients.textOrNull(delta, "thinking");
                    if (think != null && !think.isEmpty()) {
                        thinking.append(think);
                        listener.onThinking(think);
                    }
                    break;
                case "input_json_delta":
                    ToolCallBuilder builder = toolCalls.get(index);
                    if (builder != null) {
                        builder.arguments.append(LlmClients.nullToEmpty(
                                LlmClients.textOrNull(delta, "partial_json")));
                    }
                    break;
                default:
                    break;
            }
        }

        /**
         * 处理 content block 结束事件，工具参数此时已完整，输出并回调。
         *
         * @param root     事件 JSON 根节点
         * @param listener 流式响应监听器
         */
        private void handleBlockStop(JsonNode root, LlmStreamListener listener) {
            int index = root.path("index").asInt(0);
            ToolCallBuilder builder = toolCalls.remove(index);
            if (builder != null) {
                LlmToolCall toolCall = builder.snapshot();
                completedToolCalls.add(toolCall);
                listener.onToolCall(toolCall);
            }
        }

        /**
         * 构造流式调用的聚合结果。
         *
         * @return 本次流式调用的完整结果
         */
        @Override
        public LlmResponse buildResponse() {
            LlmUsage usage = inputTokens == 0 && outputTokens == 0
                    ? null
                    : new LlmUsage(inputTokens, outputTokens, inputTokens + outputTokens);
            return new LlmResponse(LlmClients.nullableString(content), LlmClients.nullableString(thinking),
                    completedToolCalls, usage, stopReason);
        }
    }

    /**
     * 流式过程中累加单个工具调用的可变载体。
     *
     * @author zcd
     */
    private static final class ToolCallBuilder {

        /** 工具调用归属的 content block index。 */
        private final Integer index;

        /** 工具调用 id。 */
        private final String id;

        /** 工具名。 */
        private final String name;

        /** 累计的参数片段。 */
        private final StringBuilder arguments = new StringBuilder();

        /**
         * 构造工具调用累加器。
         *
         * @param index content block index
         * @param id    工具调用 id
         * @param name  工具名
         */
        private ToolCallBuilder(Integer index, String id, String name) {
            this.index = index;
            this.id = id;
            this.name = name;
        }

        /**
         * 取当前状态的不可变快照。
         *
         * @return 工具调用快照
         */
        private LlmToolCall snapshot() {
            return new LlmToolCall(index, id, name, arguments.toString());
        }
    }
}
