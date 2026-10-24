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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 `/chat/completions` 映射（D16）。只做 JSON ↔ 值对象，不发请求。
 * 规则出处：技术决策 D16 第 2/3/4 条、M12。
 */
final class ChatCompletionsProtocol {

    static final String PATH = "/chat/completions";

    private final ObjectMapper mapper = new ObjectMapper();

    String buildRequest(ModelInvocationRequest request, String modelName, boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", stream);

        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isEmpty()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        for (ChatMessage message : request.messages()) {
            messages.add(toJson(message));
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSchemaSnapshot tool : request.tools()) {
                ObjectNode wrapper = tools.addObject();
                wrapper.put("type", "function");
                ObjectNode function = wrapper.putObject("function");
                function.put("name", tool.getName());
                function.put("description", tool.getDescription());
                function.set("parameters", parseSchema(tool.getParametersJsonSchema()));
            }
        }
        return root.toString();
    }

    private ObjectNode toJson(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", wireRole(message));
        if (message.content() != null) {
            node.put("content", message.content());
        }
        if (message.toolCallId() != null) {
            node.put("tool_call_id", message.toolCallId());
        }
        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.callId());
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.argumentsJson());
            }
        }
        return node;
    }

    private String wireRole(ChatMessage message) {
        RecordRole role = message.role();
        if (role == RecordRole.SYSTEM) {
            return "system";
        }
        if (role == RecordRole.ASSISTANT || role == RecordRole.TOOL_REQUEST) {
            return "assistant";
        }
        if (role == RecordRole.TOOL_RESULT && message.toolCallId() != null) {
            return "tool";
        }
        // SUMMARY / 无 callId 的工具结果没有协议对应角色，按用户消息回灌
        return "user";
    }

    ModelInvocationResult parseResponse(String body, ModelId servedModelId) {
        JsonNode root = read(body);
        JsonNode message = root.path("choices").path(0).path("message");
        String text = message.path("content").isNull() ? null : message.path("content").asText(null);
        return new ModelInvocationResult(text, parseToolCalls(message.path("tool_calls")),
                parseUsage(root.path("usage")), servedModelId);
    }

    private List<ToolCall> parseToolCalls(JsonNode calls) {
        List<ToolCall> out = new ArrayList<>();
        if (!calls.isArray()) {
            return out;
        }
        for (JsonNode call : calls) {
            out.add(new ToolCall(call.path("id").asText(), call.path("function").path("name").asText(),
                    call.path("function").path("arguments").asText()));
        }
        return out;
    }

    private ModelUsage parseUsage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return new ModelUsage(usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
    }

    private JsonNode parseSchema(String schemaJson) {
        try {
            return schemaJson == null || schemaJson.trim().isEmpty()
                    ? mapper.createObjectNode()
                    : mapper.readTree(schemaJson);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new ModelResponseFormatException("模型响应不是合法 JSON", e);
        }
    }

    /** 流式增量累加：文本片段与按 index 归属的 tool_calls 片段。规则出处：D06、D16-3。 */
    final class StreamAccumulator {

        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, CallBuilder> calls = new LinkedHashMap<>();
        private ModelUsage usage;
        private String lastDelta;

        /** 返回 false 表示这是最后一条（`[DONE]`）。 */
        boolean accept(String data) {
            if ("[DONE]".equals(data)) {
                return false;
            }
            JsonNode chunk = read(data);
            JsonNode usageNode = chunk.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage = parseUsage(usageNode);
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");
            lastDelta = null;
            if (!content.isMissingNode() && !content.isNull()) {
                String piece = content.asText();
                text.append(piece);
                lastDelta = piece;
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    CallBuilder builder = calls.get(call.path("index").asInt());
                    if (builder == null) {
                        builder = new CallBuilder();
                        calls.put(call.path("index").asInt(), builder);
                    }
                    builder.append(call);
                }
            }
            return true;
        }

        /** 当前这条 chunk 的文本增量；仅供外壳打字机效果，不回灌给模型。 */
        String lastDelta() {
            return lastDelta;
        }

        ModelInvocationResult result(ModelId servedModelId) {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (CallBuilder builder : calls.values()) {
                toolCalls.add(builder.build());
            }
            return new ModelInvocationResult(text.length() == 0 ? null : text.toString(), toolCalls, usage,
                    servedModelId);
        }
    }

    private static final class CallBuilder {

        private String callId;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void append(JsonNode call) {
            if (callId == null && call.path("id").isTextual()) {
                callId = call.path("id").asText();
            }
            JsonNode function = call.path("function");
            if (name == null && function.path("name").isTextual()) {
                name = function.path("name").asText();
            }
            if (function.path("arguments").isTextual()) {
                arguments.append(function.path("arguments").asText());
            }
        }

        ToolCall build() {
            return new ToolCall(callId, name, arguments.toString());
        }
    }
}
