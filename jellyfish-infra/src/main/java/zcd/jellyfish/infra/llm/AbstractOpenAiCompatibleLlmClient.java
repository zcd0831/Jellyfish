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
 * OpenAI Chat Completions 协议实现基类。
 * <p>
 * OpenAI、DeepSeek、MiniMax 等均兼容该协议，子类只需提供默认 baseUrl。
 *
 * @author zcd
 */
public abstract class AbstractOpenAiCompatibleLlmClient extends AbstractHttpLlmClient {

    /**
     * 构造 OpenAI 兼容协议客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    protected AbstractOpenAiCompatibleLlmClient(Provider provider, OkHttpClient httpClient,
                                                ExecutorService streamExecutor) {
        super(provider, httpClient, streamExecutor);
    }

    /**
     * 获取 provider 未配置 baseUrl 时使用的默认地址。
     *
     * @return 默认 baseUrl
     */
    protected abstract String defaultBaseUrl();

    /**
     * 解析实际使用的 baseUrl：优先取 provider 配置，缺省时回退到 {@link #defaultBaseUrl()}。
     *
     * @return 去掉尾部斜杠的 baseUrl
     */
    protected String baseUrl() {
        return LlmClients.resolveBaseUrl(provider, defaultBaseUrl());
    }

    /**
     * 构造 chat completions 接口地址。
     *
     * @return chat completions 完整地址
     */
    protected String chatCompletionsUrl() {
        return LlmClients.appendVersion(baseUrl(), "v1", "/chat/completions");
    }

    /**
     * 构造带 Bearer 鉴权头的请求构建器。
     *
     * @param url 请求地址
     * @return 已带鉴权头的请求构建器
     */
    protected Request.Builder authorizedRequest(String url) {
        return jsonRequest(url).header("Authorization", "Bearer " + LlmClients.requireApiKey(provider));
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /**
     * 同步（非流式）对话调用。
     *
     * @param request 统一请求模型
     * @return 统一返回结果
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        requireRequest(request);
        Request httpRequest = authorizedRequest(chatCompletionsUrl())
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
        Request httpRequest = authorizedRequest(chatCompletionsUrl())
                .header("Accept", "text/event-stream")
                .post(jsonBody(buildRequestBody(request, true)))
                .build();
        return startStream(httpRequest, listener, new OpenAiStreamDecoder());
    }

    /**
     * 构造 chat completions 请求体。
     *
     * @param request 统一请求模型，不可为空
     * @param stream  是否为流式请求
     * @return 可直接序列化为 JSON 的请求体
     * @throws JellyfishException 请求为 {@code null} 时抛出
     */
    protected Map<String, Object> buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("stream", stream);
        if (stream) {
            // 只有显式声明 include_usage，流末才会下发 usage，否则流式调用的 token 用量始终缺失
            body.put("stream_options", Collections.singletonMap("include_usage", true));
        }
        body.put("messages", buildMessages(request));
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (!request.getStop().isEmpty()) {
            body.put("stop", request.getStop());
        }
        if (request.hasTools()) {
            body.put("tools", buildTools(request.getTools()));
            if (LlmClients.isNotBlank(request.getToolChoice())) {
                body.put("tool_choice", request.getToolChoice().trim());
            }
        }
        return body;
    }

    /**
     * 把统一消息模型转换为 OpenAI 的 messages 数组。
     *
     * @param request 统一请求模型
     * @return OpenAI messages 数组
     */
    private List<Map<String, Object>> buildMessages(LlmRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (LlmClients.isNotBlank(request.getSystemPrompt())) {
            messages.add(textMessage(LlmMessage.ROLE_SYSTEM, request.getSystemPrompt()));
        }
        for (LlmMessage message : request.getMessages()) {
            messages.add(toRequestMessage(message));
        }
        return messages;
    }

    /**
     * 把单条统一消息转换为 OpenAI 消息结构，按角色分别处理工具结果与工具调用。
     *
     * @param message 统一消息模型
     * @return OpenAI 消息结构
     */
    private Map<String, Object> toRequestMessage(LlmMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        String role = message.getRole();
        result.put("role", role);
        if (LlmMessage.ROLE_TOOL.equals(role)) {
            result.put("tool_call_id", message.getToolCallId());
            if (LlmClients.isNotBlank(message.getName())) {
                result.put("name", message.getName());
            }
            result.put("content", LlmClients.nullToEmpty(message.getContent()));
            return result;
        }
        if (LlmMessage.ROLE_ASSISTANT.equals(role) && message.hasToolCalls()) {
            result.put("content", message.getContent());
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (LlmToolCall toolCall : message.getToolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", toolCall.getName());
                function.put("arguments", LlmClients.nullToEmpty(toolCall.getArguments()));
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", toolCall.getId());
                call.put("type", "function");
                call.put("function", function);
                toolCalls.add(call);
            }
            result.put("tool_calls", toolCalls);
            return result;
        }
        result.put("content", LlmClients.nullToEmpty(message.getContent()));
        return result;
    }

    /**
     * 构造一条纯文本消息。
     *
     * @param role    角色
     * @param content 文本内容
     * @return OpenAI 消息结构
     */
    private static Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /**
     * 把统一工具定义转换为 OpenAI 的 tools 数组。
     *
     * @param tools 工具定义
     * @return OpenAI tools 数组
     */
    private List<Map<String, Object>> buildTools(List<LlmTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>(tools.size());
        for (LlmTool tool : tools) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", tool.getParameters());
            if (!tool.getRequired().isEmpty()) {
                parameters.put("required", tool.getRequired());
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", parameters);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            result.add(entry);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 非流式响应
    // ------------------------------------------------------------------

    /**
     * 解析非流式响应。
     *
     * @param root 响应 JSON 根节点
     * @return 统一返回结果
     * @throws JellyfishException 响应中没有 choices 时抛出
     */
    protected LlmResponse parseResponse(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new JellyfishException(
                    "response has no choices from provider: " + provider.getName());
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = LlmClients.textOrNull(message, "content");
        String thinking = LlmClients.firstNonBlank(
                LlmClients.textOrNull(message, "reasoning_content"),
                LlmClients.textOrNull(message, "reasoning"));
        List<LlmToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        return new LlmResponse(content, thinking, toolCalls,
                parseUsage(root.path("usage")), LlmClients.textOrNull(choice, "finish_reason"));
    }

    /**
     * 解析响应中的 tool_calls 数组。缺失 index 时按出现顺序编号。
     *
     * @param toolCalls tool_calls JSON 节点
     * @return 工具调用列表，可能为空但不会为 {@code null}
     */
    protected static List<LlmToolCall> parseToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        List<LlmToolCall> result = new ArrayList<>(toolCalls.size());
        int index = 0;
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            Integer callIndex = toolCall.hasNonNull("index") ? toolCall.get("index").asInt() : index;
            result.add(new LlmToolCall(callIndex, LlmClients.textOrNull(toolCall, "id"),
                    LlmClients.textOrNull(function, "name"), LlmClients.textOrNull(function, "arguments")));
            index++;
        }
        return result;
    }

    /**
     * 解析 OpenAI 兼容协议的 usage 节点。
     * <p>
     * <b>不需要归一化输入</b>：{@code prompt_tokens} 本就已经包含缓存命中部分。但缓存字段名两家不同——
     * OpenAI 放在 {@code prompt_tokens_details.cached_tokens}，DeepSeek 直接给
     * {@code prompt_cache_hit_tokens}（官方文档：{@code prompt_tokens} = 命中 + 未命中）。
     * 两个都看一眼、取有值的那个：比为每家在子类里各写一份解析便宜，也不会因为某家换字段而静默漏读。
     *
     * @param usage usage JSON 节点
     * @return token 使用量，厂商未返回时为 {@code null}
     */
    protected static LlmUsage parseUsage(JsonNode usage) {
        if (isMissingUsage(usage)) {
            return null;
        }
        JsonNode details = usage.path("prompt_tokens_details");
        int cacheRead = Math.max(intField(usage, "prompt_cache_hit_tokens", 0),
                intField(details, "cached_tokens", 0));
        return usageOf(intField(usage, "prompt_tokens", 0), intField(usage, "completion_tokens", 0),
                intField(usage, "total_tokens", 0), cacheRead,
                intField(details, "cache_write_tokens", 0));
    }

    // ------------------------------------------------------------------
    // 流式响应
    // ------------------------------------------------------------------

    /**
     * OpenAI 兼容协议的流式解码器，负责把 delta 分片累加成完整结果。
     *
     * @author zcd
     */
    private final class OpenAiStreamDecoder implements StreamDecoder {

        /** 累计的文本回复。 */
        private final StringBuilder content = new StringBuilder();

        /** 累计的思考过程。 */
        private final StringBuilder thinking = new StringBuilder();

        /** 累计的工具调用。 */
        private final StreamToolCallAccumulator toolCalls = new StreamToolCallAccumulator();

        /** 最近一次非空的 token 使用量。 */
        private LlmUsage usage;

        /** 结束原因。 */
        private String finishReason;

        /**
         * 处理一个流式分片。
         *
         * @param event    SSE 事件名，OpenAI 兼容协议一般为空
         * @param data     分片 JSON
         * @param listener 流式响应监听器
         * @return 恒为 {@code false}，由 {@code [DONE]} 标记结束
         */
        @Override
        public boolean onData(String event, String data, LlmStreamListener listener) {
            JsonNode root = ObjectMapperWrapper.readValue(data, JsonNode.class);
            LlmUsage chunkUsage = parseUsage(root.path("usage"));
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return false;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            String text = LlmClients.textOrNull(delta, "content");
            if (text != null && !text.isEmpty()) {
                content.append(text);
                listener.onText(text);
            }
            String reasoning = LlmClients.firstNonBlank(
                    LlmClients.textOrNull(delta, "reasoning_content"),
                    LlmClients.textOrNull(delta, "reasoning"));
            if (reasoning != null && !reasoning.isEmpty()) {
                thinking.append(reasoning);
                listener.onThinking(reasoning);
            }
            JsonNode toolCallNodes = delta.path("tool_calls");
            if (toolCallNodes.isArray()) {
                for (JsonNode toolCall : toolCallNodes) {
                    Integer index = toolCall.hasNonNull("index") ? toolCall.get("index").asInt() : null;
                    JsonNode function = toolCall.path("function");
                    LlmToolCall merged = toolCalls.merge(index,
                            LlmClients.textOrNull(toolCall, "id"),
                            LlmClients.textOrNull(function, "name"),
                            LlmClients.textOrNull(function, "arguments"));
                    listener.onToolCall(merged);
                }
            }
            String chunkFinishReason = LlmClients.textOrNull(choice, "finish_reason");
            if (chunkFinishReason != null) {
                finishReason = chunkFinishReason;
            }
            return false;
        }

        /**
         * 构造流式调用的聚合结果。
         *
         * @return 本次流式调用的完整结果
         */
        @Override
        public LlmResponse buildResponse() {
            return new LlmResponse(LlmClients.nullableString(content), LlmClients.nullableString(thinking),
                    toolCalls.toList(), usage, finishReason);
        }
    }
}
