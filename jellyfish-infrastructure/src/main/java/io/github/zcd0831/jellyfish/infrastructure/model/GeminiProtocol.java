package io.github.zcd0831.jellyfish.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Google Gemini 映射：`/v1beta/models/{model}:generateContent`（流式 `:streamGenerateContent?alt=sse`），
 * `contents[]` 的 user/model 轮次、`functionCall` / `functionResponse` 报价按 name + 顺序配对。
 * 规则出处：模块-行动 §8.2 第 7 条、模块-基础设施层 §10。
 */
public final class GeminiProtocol implements ChatProtocolAdapter {

    static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com";
    /** Gemini 的 JSON Schema 只支持子集：这些键会被拒。 */
    private static final List<String> UNSUPPORTED_SCHEMA_KEYS = Arrays.asList(
            "additionalProperties", "$schema", "$id", "title", "default", "examples", "definitions");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String buildRequest(ModelInvocationRequest request, String modelName, int maxOutputTokens,
                               boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        if (hasText(request.systemPrompt())) {
            ObjectNode system = root.putObject("systemInstruction");
            system.putArray("parts").addObject().put("text", request.systemPrompt());
        }
        Map<String, String> nameByCallId = namesByCallId(request.messages());
        List<Wire> wire = new ArrayList<Wire>();
        for (ChatMessage message : request.messages()) {
            if (message.role() == RecordRole.TOOL_RESULT) {
                appendFunctionResponse(wire, message, nameByCallId);
            } else if (message.role() == RecordRole.ASSISTANT) {
                appendModel(wire, message);
            } else {
                appendUserText(wire, message.content());
            }
        }
        ArrayNode contents = root.putArray("contents");
        if (!wire.isEmpty() && "model".equals(wire.get(0).role)) {
            // Gemini 期望首轮是 user；历史裁剪后首轮可能落在 model 上
            ObjectNode lead = contents.addObject();
            lead.put("role", "user");
            lead.putArray("parts").addObject().put("text", "（继续）");
        }
        for (Wire message : wire) {
            ObjectNode node = contents.addObject();
            node.put("role", message.role);
            node.set("parts", message.parts);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            ArrayNode declarations = tools.addObject().putArray("functionDeclarations");
            for (ToolSchemaSnapshot tool : request.tools()) {
                ObjectNode declaration = declarations.addObject();
                declaration.put("name", tool.getName());
                declaration.put("description", tool.getDescription());
                declaration.set("parameters", sanitize(parseSchema(tool.getParametersJsonSchema())));
            }
        }
        if (maxOutputTokens > 0) {
            root.putObject("generationConfig").put("maxOutputTokens", maxOutputTokens);
        }
        return root.toString();
    }

    private Map<String, String> namesByCallId(List<ChatMessage> messages) {
        Map<String, String> names = new HashMap<String, String>();
        for (ChatMessage message : messages) {
            for (ToolCall call : message.toolCalls()) {
                names.put(call.callId(), call.name());
            }
        }
        return names;
    }

    private void appendUserText(List<Wire> wire, String text) {
        if (!hasText(text)) {
            return;
        }
        lastWithRole(wire, "user").parts.addObject().put("text", text);
    }

    private void appendModel(List<Wire> wire, ChatMessage message) {
        List<ObjectNode> parts = new ArrayList<ObjectNode>();
        if (hasText(message.content())) {
            parts.add(mapper.createObjectNode().put("text", message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            ObjectNode functionCall = mapper.createObjectNode();
            functionCall.put("name", call.name());
            functionCall.set("args", parseArguments(call.argumentsJson()));
            parts.add(mapper.createObjectNode().set("functionCall", functionCall));
        }
        if (parts.isEmpty()) {
            return;
        }
        Wire target = lastWithRole(wire, "model");
        for (ObjectNode part : parts) {
            target.parts.add(part);
        }
    }

    /** `functionResponse` 用 name 配对（Gemini 传统上没有 callId），响应必须是对象。 */
    private void appendFunctionResponse(List<Wire> wire, ChatMessage message, Map<String, String> nameByCallId) {
        String name = nameByCallId.get(message.toolCallId());
        ObjectNode functionResponse = mapper.createObjectNode();
        functionResponse.put("name", name == null ? "tool" : name);
        ObjectNode response = functionResponse.putObject("response");
        response.put("result", message.content() == null ? "" : message.content());
        lastWithRole(wire, "user").parts.add(mapper.createObjectNode().set("functionResponse", functionResponse));
    }

    private Wire lastWithRole(List<Wire> wire, String role) {
        if (!wire.isEmpty() && wire.get(wire.size() - 1).role.equals(role)) {
            return wire.get(wire.size() - 1);
        }
        Wire created = new Wire(role);
        wire.add(created);
        return created;
    }

    @Override
    public ModelInvocationResult parseResponse(String body, ModelId servedModelId) {
        JsonNode root = read(body);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode part : parts) {
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            } else if (part.has("functionCall")) {
                calls.add(toCall(part.path("functionCall")));
            }
        }
        return new ModelInvocationResult(text.length() == 0 ? null : text.toString(), calls, null,
                usage(root.path("usageMetadata")), servedModelId);
    }

    @Override
    public StreamAccumulator newStreamAccumulator() {
        return new StreamAccumulator();
    }

    @Override
    public String url(String baseEndpoint, String modelName, String apiKey, boolean stream) {
        String base = hasText(baseEndpoint) ? baseEndpoint : DEFAULT_BASE;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.contains("/v1beta") && !base.contains(":generateContent")
                && !base.contains(":streamGenerateContent")) {
            base = base + "/v1beta";
        }
        String name = modelName != null && modelName.startsWith("models/")
                ? modelName.substring("models/".length()) : modelName;
        String method = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        return base + "/models/" + name + method;
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-goog-api-key", apiKey);
        return headers;
    }

    private static ModelUsage usage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return new ModelUsage(usage.path("promptTokenCount").asLong(),
                usage.path("candidatesTokenCount").asLong());
    }

    /** 流式：每帧 `candidates[0].content.parts[]`；functionCall 按到达顺序收集（相同调用去重）。 */
    public final class StreamAccumulator implements ChatProtocolAdapter.StreamAccumulator {

        private final StringBuilder text = new StringBuilder();
        private final List<ToolCall> calls = new ArrayList<ToolCall>();
        private ModelUsage usage;
        private String lastDelta;
        private String lastName;
        private String lastArguments;

        @Override
        public boolean accept(String data) {
            if ("[DONE]".equals(data)) {
                return false;
            }
            JsonNode root = read(data);
            lastDelta = null;
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            for (JsonNode part : parts) {
                if (part.path("text").isTextual()) {
                    String piece = part.path("text").asText();
                    if (!piece.isEmpty()) {
                        text.append(piece);
                        lastDelta = piece;
                    }
                } else if (part.has("functionCall")) {
                    ToolCall call = toCall(part.path("functionCall"));
                    if (!sameAsLast(call)) {
                        calls.add(call);
                        lastName = call.name();
                        lastArguments = call.argumentsJson();
                    }
                }
            }
            JsonNode usageNode = root.path("usageMetadata");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage = usage(usageNode);
            }
            return true;
        }

        private boolean sameAsLast(ToolCall call) {
            return call.name().equals(lastName) && call.argumentsJson().equals(lastArguments);
        }

        @Override
        public String lastDelta() {
            return lastDelta;
        }

        @Override
        public ModelInvocationResult result(ModelId servedModelId) {
            return new ModelInvocationResult(text.length() == 0 ? null : text.toString(),
                    new ArrayList<ToolCall>(calls), null, usage, servedModelId);
        }
    }

    private ToolCall toCall(JsonNode functionCall) {
        String id = functionCall.path("id").isTextual() ? functionCall.path("id").asText()
                : "call_" + UUID.randomUUID().toString();
        JsonNode args = functionCall.path("args");
        return new ToolCall(id, functionCall.path("name").asText(),
                args.isMissingNode() ? "{}" : args.toString());
    }

    private JsonNode parseArguments(String argumentsJson) {
        try {
            JsonNode node = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (Exception broken) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode parseSchema(String schemaJson) {
        try {
            return hasText(schemaJson) ? mapper.readTree(schemaJson) : mapper.createObjectNode();
        } catch (Exception broken) {
            return mapper.createObjectNode();
        }
    }

    /** 去掉 Gemini 不支持的 JSON Schema 键（否则 tool 声明会被拒）。 */
    private JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!UNSUPPORTED_SCHEMA_KEYS.contains(field.getKey())) {
                    out.set(field.getKey(), sanitize(field.getValue()));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode item : node) {
                out.add(sanitize(item));
            }
            return out;
        }
        return node;
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new ModelResponseFormatException("模型响应不是合法 JSON", e);
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private final class Wire {

        private final String role;
        private final ArrayNode parts = mapper.createArrayNode();

        Wire(String role) {
            this.role = role;
        }
    }
}
