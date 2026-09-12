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
 * Google Gemini 原生实现（generateContent / streamGenerateContent），并支持 listModels 与 embedContent。
 *
 * @author zcd
 */
public class GeminiLlmClient extends AbstractHttpLlmClient {

    /** Gemini 官方默认 baseUrl。 */
    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** Gemini REST API 版本段。 */
    private static final String API_VERSION = "v1beta";

    /** 未指定向量化模型时使用的默认模型。 */
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-004";

    /** Gemini 的 functionCall 不带 id，这里用该前缀拼出本地工具调用 id。 */
    private static final String GEMINI_TOOL_CALL_PREFIX = "gemini_call_";

    /**
     * 构造 Gemini 客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    public GeminiLlmClient(Provider provider, OkHttpClient httpClient, ExecutorService streamExecutor) {
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
     * 同步（非流式）对话调用。
     *
     * @param request 统一请求模型
     * @return 统一返回结果
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        requireRequest(request);
        Request httpRequest = geminiRequest(request, ":generateContent");
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
        Request httpRequest = geminiRequest(request, ":streamGenerateContent?alt=sse");
        return startStream(httpRequest, listener, new GeminiStreamDecoder());
    }

    /**
     * 拉取可用模型列表，返回去掉 {@code models/} 前缀的模型名。
     *
     * @return 模型名列表，响应结构非法时返回空列表
     */
    @Override
    public List<String> listModels() {
        Request request = jsonRequest(LlmClients.appendVersion(baseUrl(), API_VERSION, "/models?pageSize=1000"))
                .header("x-goog-api-key", LlmClients.requireApiKey(provider))
                .get()
                .build();
        JsonNode root = executeForJson(request, JsonNode.class, "list models request");
        JsonNode models = root.path("models");
        if (!models.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(models.size());
        for (JsonNode model : models) {
            String name = LlmClients.textOrNull(model, "name");
            if (name != null) {
                result.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
            }
        }
        return result;
    }

    /**
     * 调用 embedContent 接口做文本向量化。
     *
     * @param input 待向量化的文本
     * @return 向量
     * @throws JellyfishException 响应结构非法或请求失败时抛出
     */
    @Override
    public double[] embedding(String input) {
        String model = DEFAULT_EMBEDDING_MODEL;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", Collections.singletonList(Collections.singletonMap("text", input)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "models/" + model);
        body.put("content", content);
        Request request = geminiRequest(model, ":embedContent").newBuilder()
                .post(jsonBody(body))
                .build();
        JsonNode root = executeForJson(request, JsonNode.class, "embedding request");
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray()) {
            throw new JellyfishException("embedding response is invalid for provider: " + provider.getName());
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).asDouble();
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /**
     * 构造带请求体的 Gemini 请求。
     *
     * @param request      统一请求模型
     * @param methodSuffix 方法后缀，形如 {@code :generateContent}
     * @return 完整的 HTTP 请求
     */
    private Request geminiRequest(LlmRequest request, String methodSuffix) {
        return geminiRequest(request.getModel(), methodSuffix).newBuilder()
                .post(jsonBody(buildRequestBody(request)))
                .build();
    }

    /**
     * 构造不带请求体的 Gemini 请求（GET / 由调用方补请求体）。
     *
     * @param model        模型名，可为带 {@code models/} 前缀的形式
     * @param methodSuffix 方法后缀，形如 {@code :generateContent}
     * @return 完整的 HTTP 请求
     */
    private Request geminiRequest(String model, String methodSuffix) {
        String url = LlmClients.appendVersion(baseUrl(), API_VERSION,
                "/models/" + stripModelPrefix(model) + methodSuffix);
        return jsonRequest(url).header("x-goog-api-key", LlmClients.requireApiKey(provider)).build();
    }

    /**
     * 去除模型名的 {@code models/} 前缀，避免拼出重复路径段。
     *
     * @param model 模型名
     * @return 不带前缀的模型名
     */
    private static String stripModelPrefix(String model) {
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    /**
     * 构造 generateContent 请求体。
     *
     * @param request 统一请求模型
     * @return 可直接序列化为 JSON 的请求体
     */
    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", buildContents(request));
        String systemInstruction = collectSystemPrompt(request);
        if (LlmClients.isNotBlank(systemInstruction)) {
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("parts", Collections.singletonList(Collections.singletonMap("text", systemInstruction)));
            body.put("systemInstruction", system);
        }
        Map<String, Object> generationConfig = buildGenerationConfig(request);
        if (!generationConfig.isEmpty()) {
            body.put("generationConfig", generationConfig);
        }
        if (request.hasTools()) {
            body.put("tools", buildTools(request.getTools()));
            Map<String, Object> toolConfig = buildToolConfig(request.getToolChoice());
            if (!toolConfig.isEmpty()) {
                body.put("toolConfig", toolConfig);
            }
        }
        return body;
    }

    /**
     * 把统一消息模型转换为 Gemini contents 数组。
     *
     * @param request 统一请求模型
     * @return Gemini contents 数组
     */
    private List<Map<String, Object>> buildContents(LlmRequest request) {
        Map<String, String> toolNames = collectToolNames(request);
        List<Map<String, Object>> contents = new ArrayList<>();
        for (LlmMessage message : request.getMessages()) {
            String role = message.getRole();
            if (LlmMessage.ROLE_SYSTEM.equals(role)) {
                continue;
            }
            if (LlmMessage.ROLE_TOOL.equals(role)) {
                contents.add(functionResponseContent(message, toolNames));
            } else if (LlmMessage.ROLE_ASSISTANT.equals(role)) {
                // content 与工具调用都为空时会产生 parts 为空的非法 content，直接跳过
                if (LlmClients.isNotBlank(message.getContent()) || message.hasToolCalls()) {
                    contents.add(assistantContent(message));
                }
            } else {
                contents.add(textContent("user", LlmClients.nullToEmpty(message.getContent())));
            }
        }
        return contents;
    }

    /**
     * 收集工具调用 id 到工具名的映射。
     * <p>
     * Gemini 的 functionResponse 必须带 name，而工具结果消息不一定携带；此时用该映射按
     * toolCallId 回填。
     *
     * @param request 统一请求模型
     * @return 工具调用 id 到工具名的映射
     */
    private static Map<String, String> collectToolNames(LlmRequest request) {
        Map<String, String> toolNames = new LinkedHashMap<>();
        for (LlmMessage message : request.getMessages()) {
            if (!LlmMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                continue;
            }
            for (LlmToolCall toolCall : message.getToolCalls()) {
                if (LlmClients.isNotBlank(toolCall.getId()) && LlmClients.isNotBlank(toolCall.getName())) {
                    toolNames.put(toolCall.getId(), toolCall.getName());
                }
            }
        }
        return toolNames;
    }

    /**
     * 构造一条纯文本 content。
     *
     * @param role 角色，Gemini 使用 {@code user} / {@code model}
     * @param text 文本内容
     * @return Gemini content 结构
     */
    private static Map<String, Object> textContent(String role, String text) {
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("parts", Collections.singletonList(Collections.singletonMap("text", text)));
        parts.put("role", role);
        return parts;
    }

    /**
     * 把模型回复转换为 Gemini content（text 与 functionCall 混合的 parts）。
     *
     * @param message 模型回复消息
     * @return Gemini model content 结构
     */
    private static Map<String, Object> assistantContent(LlmMessage message) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (LlmClients.isNotBlank(message.getContent())) {
            parts.add(Collections.<String, Object>singletonMap("text", message.getContent()));
        }
        for (LlmToolCall toolCall : message.getToolCalls()) {
            Map<String, Object> functionCall = new LinkedHashMap<>();
            functionCall.put("name", toolCall.getName());
            functionCall.put("args", LlmClients.parseArguments(toolCall.getArguments()));
            parts.add(Collections.<String, Object>singletonMap("functionCall", functionCall));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", parts);
        return content;
    }

    /**
     * 把工具结果消息转换为 Gemini functionResponse content。
     * <p>
     * Gemini 要求 functionResponse.name 必填，优先取工具结果消息自带的 name，缺失时按 toolCallId
     * 从历史工具调用中回填；两者都拿不到时直接抛异常，避免下发非法请求。
     *
     * @param message   工具结果消息
     * @param toolNames 工具调用 id 到工具名的映射
     * @return Gemini user content 结构
     * @throws JellyfishException 无法确定工具名时抛出
     */
    private static Map<String, Object> functionResponseContent(LlmMessage message, Map<String, String> toolNames) {
        String name = LlmClients.isNotBlank(message.getName())
                ? message.getName()
                : toolNames.get(message.getToolCallId());
        if (!LlmClients.isNotBlank(name)) {
            throw new JellyfishException(
                    "function response name is required for provider, toolCallId: " + message.getToolCallId());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", LlmClients.nullToEmpty(message.getContent()));
        Map<String, Object> functionResponse = new LinkedHashMap<>();
        functionResponse.put("name", name);
        functionResponse.put("response", response);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", Collections.singletonList(
                Collections.<String, Object>singletonMap("functionResponse", functionResponse)));
        return content;
    }

    /**
     * 构造 generationConfig，仅在请求设置了对应字段时填充。
     *
     * @param request 统一请求模型
     * @return generationConfig，无任何设置时返回空 Map
     */
    private static Map<String, Object> buildGenerationConfig(LlmRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (request.getTemperature() != null) {
            config.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            config.put("topP", request.getTopP());
        }
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            config.put("maxOutputTokens", request.getMaxTokens());
        }
        if (!request.getStop().isEmpty()) {
            config.put("stopSequences", request.getStop());
        }
        return config;
    }

    /**
     * 把统一工具定义转换为 Gemini functionDeclarations 结构。
     *
     * @param tools 工具定义
     * @return Gemini tools 数组
     */
    private static List<Map<String, Object>> buildTools(List<LlmTool> tools) {
        List<Map<String, Object>> declarations = new ArrayList<>(tools.size());
        for (LlmTool tool : tools) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", tool.getParameters());
            if (!tool.getRequired().isEmpty()) {
                parameters.put("required", tool.getRequired());
            }
            Map<String, Object> declaration = new LinkedHashMap<>();
            declaration.put("name", tool.getName());
            declaration.put("description", tool.getDescription());
            declaration.put("parameters", parameters);
            declarations.add(declaration);
        }
        return Collections.singletonList(
                Collections.<String, Object>singletonMap("functionDeclarations", declarations));
    }

    /**
     * 把统一工具选择策略转换为 Gemini functionCallingConfig。
     *
     * @param toolChoice 统一工具选择策略
     * @return Gemini toolConfig，无需下发时返回空 Map
     */
    private static Map<String, Object> buildToolConfig(String toolChoice) {
        String choice = LlmClients.normalizeToolChoice(toolChoice);
        if (choice.isEmpty()) {
            return Collections.emptyMap();
        }
        String mode;
        switch (choice) {
            case "none":
                mode = "NONE";
                break;
            case "required":
            case "any":
                mode = "ANY";
                break;
            case "auto":
            default:
                mode = "AUTO";
                break;
        }
        Map<String, Object> functionCallingConfig = new LinkedHashMap<>();
        functionCallingConfig.put("mode", mode);
        return Collections.singletonMap("functionCallingConfig", functionCallingConfig);
    }

    // ------------------------------------------------------------------
    // 非流式响应
    // ------------------------------------------------------------------

    /**
     * 解析非流式响应，按 part 类型分别归入文本、思考过程与工具调用。
     *
     * @param root 响应 JSON 根节点
     * @return 统一返回结果
     * @throws JellyfishException 响应中没有 candidates 时抛出
     */
    private LlmResponse parseResponse(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new JellyfishException("response has no candidates from provider: " + provider.getName());
        }
        JsonNode candidate = candidates.get(0);
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        List<LlmToolCall> toolCalls = new ArrayList<>();
        int index = 0;
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode functionCall = part.path("functionCall");
                if (!functionCall.isMissingNode() && !functionCall.isNull()) {
                    String name = LlmClients.textOrNull(functionCall, "name");
                    String arguments = LlmClients.toJson(functionCall.path("args"));
                    toolCalls.add(new LlmToolCall(index, GEMINI_TOOL_CALL_PREFIX + index, name, arguments));
                    index++;
                    continue;
                }
                String piece = LlmClients.textOrNull(part, "text");
                if (piece == null) {
                    continue;
                }
                if (part.path("thought").asBoolean(false)) {
                    thinking.append(piece);
                } else {
                    text.append(piece);
                }
            }
        }
        return new LlmResponse(LlmClients.nullableString(text), LlmClients.nullableString(thinking), toolCalls,
                parseUsage(root.path("usageMetadata")), LlmClients.textOrNull(candidate, "finishReason"));
    }

    /**
     * 解析 usageMetadata 节点。三个计数字段全为 0 时视为厂商未返回。
     *
     * @param usage usageMetadata JSON 节点
     * @return token 使用量，厂商未返回时为 {@code null}
     */
    private static LlmUsage parseUsage(JsonNode usage) {
        return parseUsage(usage, "promptTokenCount", "candidatesTokenCount", "totalTokenCount");
    }

    // ------------------------------------------------------------------
    // 流式响应
    // ------------------------------------------------------------------

    /**
     * Gemini 流式解码器。Gemini 的 SSE 分片一次下发完整的 part（含 functionCall），无需参数分片累加。
     *
     * @author zcd
     */
    private final class GeminiStreamDecoder implements StreamDecoder {

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
         * @param event    SSE 事件名，Gemini 未使用
         * @param data     分片 JSON
         * @param listener 流式响应监听器
         * @return 恒为 {@code false}，由连接结束终止读取
         */
        @Override
        public boolean onData(String event, String data, LlmStreamListener listener) {
            JsonNode root = ObjectMapperWrapper.readValue(data, JsonNode.class);
            LlmUsage chunkUsage = parseUsage(root.path("usageMetadata"));
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return false;
            }
            JsonNode candidate = candidates.get(0);
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode functionCall = part.path("functionCall");
                    if (!functionCall.isMissingNode() && !functionCall.isNull()) {
                        String name = LlmClients.textOrNull(functionCall, "name");
                        String arguments = LlmClients.toJson(functionCall.path("args"));
                        LlmToolCall toolCall = toolCalls.add(null, name, arguments);
                        listener.onToolCall(toolCall);
                        continue;
                    }
                    String piece = LlmClients.textOrNull(part, "text");
                    if (piece == null || piece.isEmpty()) {
                        continue;
                    }
                    if (part.path("thought").asBoolean(false)) {
                        thinking.append(piece);
                        listener.onThinking(piece);
                    } else {
                        content.append(piece);
                        listener.onText(piece);
                    }
                }
            }
            String chunkFinishReason = LlmClients.textOrNull(candidate, "finishReason");
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
            List<LlmToolCall> calls = toolCalls.isEmpty()
                    ? Collections.<LlmToolCall>emptyList()
                    : toolCalls.toList();
            return new LlmResponse(LlmClients.nullableString(content), LlmClients.nullableString(thinking),
                    calls, usage, finishReason);
        }
    }
}
