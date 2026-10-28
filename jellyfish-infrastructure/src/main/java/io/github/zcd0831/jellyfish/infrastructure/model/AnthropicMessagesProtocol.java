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
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 映射：`/v1/messages`，顶层 `system`、content blocks、
 * `tool_use` / `tool_result` 必须同轮配对、要求 user/assistant 交替。
 * 规则出处：05-action §5.9.2 第 7 条、09-infrastructure §9.11。
 */
public final class AnthropicMessagesProtocol implements ChatProtocolAdapter {

    static final String PATH = "/v1/messages";
    static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    /** Anthropic 要求首条消息是 user；历史裁剪后首条可能落在 assistant 轮上。 */
    private static final String CONTINUE = "（继续）";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String buildRequest(ModelInvocationRequest request, String modelName, int maxOutputTokens,
                               boolean stream) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_TOKENS);
        root.put("stream", stream);
        if (hasText(request.systemPrompt())) {
            root.put("system", request.systemPrompt());
        }
        List<Wire> wire = toWire(request.messages());
        ArrayNode messages = root.putArray("messages");
        if (!wire.isEmpty() && "assistant".equals(wire.get(0).role)) {
            ObjectNode lead = messages.addObject();
            lead.put("role", "user");
            lead.putArray("content").addObject().put("type", "text").put("text", CONTINUE);
        }
        for (Wire message : wire) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role);
            node.set("content", message.content);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSchemaSnapshot tool : request.tools()) {
                ObjectNode wrapper = tools.addObject();
                wrapper.put("name", tool.getName());
                wrapper.put("description", tool.getDescription());
                wrapper.set("input_schema", parseSchema(tool.getParametersJsonSchema()));
            }
        }
        return root.toString();
    }

    /** canonical → content blocks；连续同角色合并，`tool_result` 排在 user 消息最前。 */
    private List<Wire> toWire(List<ChatMessage> messages) {
        List<Wire> wire = new ArrayList<Wire>();
        for (ChatMessage message : messages) {
            if (message.role() == io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.TOOL_RESULT) {
                appendToolResult(wire, message);
            } else if (message.role() == io.github.zcd0831.jellyfish.domain.perception.session.RecordRole.ASSISTANT) {
                appendAssistant(wire, message);
            } else {
                appendText(wire, message.content());
            }
        }
        return wire;
    }

    private void appendText(List<Wire> wire, String text) {
        if (!hasText(text)) {
            return;
        }
        Wire last = lastWithRole(wire, "user");
        last.content.addObject().put("type", "text").put("text", text);
    }

    private void appendAssistant(List<Wire> wire, ChatMessage message) {
        List<ObjectNode> blocks = new ArrayList<ObjectNode>();
        // 签名 thinking 必须原样带回（extended thinking + tools 的硬要求），且排在内容块之前
        blocks.addAll(thinkingBlocks(message.reasoning()));
        if (hasText(message.content())) {
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "text");
            block.put("text", message.content());
            blocks.add(block);
        }
        for (ToolCall call : message.toolCalls()) {
            ObjectNode block = mapper.createObjectNode();
            block.put("type", "tool_use");
            block.put("id", call.callId());
            block.put("name", call.name());
            block.set("input", parseArguments(call.argumentsJson()));
            blocks.add(block);
        }
        if (blocks.isEmpty()) {
            return;
        }
        Wire target = lastWithRole(wire, "assistant");
        for (ObjectNode block : blocks) {
            target.content.add(block);
        }
    }

    private void appendToolResult(List<Wire> wire, ChatMessage message) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", message.toolCallId() == null ? "" : message.toolCallId());
        block.put("content", message.content() == null ? "" : message.content());
        Wire target = lastWithRole(wire, "user");
        ArrayNode content = target.content;
        int pos = 0;
        while (pos < content.size() && "tool_result".equals(content.get(pos).path("type").asText())) {
            pos++;
        }
        content.insert(pos, block);
    }

    /** `reasoning` 存的是 Anthropic 自己的 thinking blocks（含 signature）；解析不了就丢弃。 */
    private List<ObjectNode> thinkingBlocks(String reasoning) {
        List<ObjectNode> blocks = new ArrayList<ObjectNode>();
        if (!hasText(reasoning)) {
            return blocks;
        }
        try {
            JsonNode parsed = mapper.readTree(reasoning);
            if (!parsed.isArray()) {
                return blocks;
            }
            for (JsonNode block : parsed) {
                if ("thinking".equals(block.path("type").asText()) && block.path("signature").isTextual()) {
                    ObjectNode copy = mapper.createObjectNode();
                    copy.put("type", "thinking");
                    copy.put("thinking", block.path("thinking").asText(""));
                    copy.put("signature", block.path("signature").asText());
                    blocks.add(copy);
                }
            }
        } catch (Exception notOurs) {
            return new ArrayList<ObjectNode>();
        }
        return blocks;
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
        StringBuilder text = new StringBuilder();
        ArrayNode thinking = mapper.createArrayNode();
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode block : root.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(),
                        block.path("input").isMissingNode() ? "{}" : block.path("input").toString()));
            } else if ("thinking".equals(type)) {
                ObjectNode copy = mapper.createObjectNode();
                copy.put("type", "thinking");
                copy.put("thinking", block.path("thinking").asText(""));
                copy.put("signature", block.path("signature").asText(""));
                thinking.add(copy);
            }
        }
        return new ModelInvocationResult(text.length() == 0 ? null : text.toString(), calls,
                thinking.size() == 0 ? null : thinking.toString(), usage(root.path("usage")), servedModelId);
    }

    @Override
    public StreamAccumulator newStreamAccumulator() {
        return new StreamAccumulator();
    }

    @Override
    public String url(String baseEndpoint, String modelName, String apiKey, boolean stream) {
        String base = stripTrailingSlash(baseEndpoint);
        return base.endsWith(PATH) ? base : base + PATH;
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    private static ModelUsage usage(JsonNode usage) {
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return new ModelUsage(usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong());
    }

    /** SSE：`content_block_delta` 累积文本与 `input_json_delta`；`message_stop` 收尾。 */
    public final class StreamAccumulator implements ChatProtocolAdapter.StreamAccumulator {

        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, CallBuilder> calls = new LinkedHashMap<Integer, CallBuilder>();
        private final Map<Integer, ThinkingBuilder> thinking = new LinkedHashMap<Integer, ThinkingBuilder>();
        private long inputTokens;
        private long outputTokens;
        private boolean hasUsage;
        private String lastDelta;

        @Override
        public boolean accept(String data) {
            JsonNode event = read(data);
            String type = event.path("type").asText();
            lastDelta = null;
            if ("message_start".equals(type)) {
                JsonNode usage = event.path("message").path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("input_tokens").asLong();
                    outputTokens = usage.path("output_tokens").asLong();
                    hasUsage = true;
                }
            } else if ("content_block_start".equals(type)) {
                JsonNode block = event.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    calls.put(event.path("index").asInt(), new CallBuilder(block.path("id").asText(),
                            block.path("name").asText()));
                } else if ("thinking".equals(block.path("type").asText())) {
                    thinking.put(event.path("index").asInt(), new ThinkingBuilder());
                } else if (block.path("text").isTextual()) {
                    append(block.path("text").asText());
                }
            } else if ("content_block_delta".equals(type)) {
                JsonNode delta = event.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    append(delta.path("text").asText(""));
                } else if ("thinking_delta".equals(delta.path("type").asText())) {
                    ThinkingBuilder builder = thinking.get(event.path("index").asInt());
                    if (builder != null) {
                        builder.thinking.append(delta.path("thinking").asText(""));
                    }
                } else if ("signature_delta".equals(delta.path("type").asText())) {
                    ThinkingBuilder builder = thinking.get(event.path("index").asInt());
                    if (builder != null) {
                        builder.signature.append(delta.path("signature").asText(""));
                    }
                } else if ("input_json_delta".equals(delta.path("type").asText())) {
                    CallBuilder builder = calls.get(event.path("index").asInt());
                    if (builder != null) {
                        builder.arguments.append(delta.path("partial_json").asText(""));
                    }
                }
            } else if ("message_delta".equals(type)) {
                JsonNode usage = event.path("usage");
                if (!usage.isMissingNode()) {
                    outputTokens = usage.path("output_tokens").asLong();
                    hasUsage = true;
                }
            } else if ("message_stop".equals(type)) {
                return false;
            }
            return true;
        }

        private void append(String piece) {
            if (piece.isEmpty()) {
                return;
            }
            text.append(piece);
            lastDelta = piece;
        }

        @Override
        public String lastDelta() {
            return lastDelta;
        }

        @Override
        public ModelInvocationResult result(ModelId servedModelId) {
            List<ToolCall> toolCalls = new ArrayList<ToolCall>();
            for (CallBuilder builder : calls.values()) {
                toolCalls.add(builder.build());
            }
            ArrayNode blocks = mapper.createArrayNode();
            for (ThinkingBuilder builder : thinking.values()) {
                ObjectNode copy = mapper.createObjectNode();
                copy.put("type", "thinking");
                copy.put("thinking", builder.thinking.toString());
                copy.put("signature", builder.signature.toString());
                blocks.add(copy);
            }
            ModelUsage usage = hasUsage ? new ModelUsage(inputTokens, outputTokens) : null;
            return new ModelInvocationResult(text.length() == 0 ? null : text.toString(), toolCalls,
                    blocks.size() == 0 ? null : blocks.toString(), usage, servedModelId);
        }
    }

    private static final class ThinkingBuilder {

        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
    }

    private static final class CallBuilder {

        private final String callId;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        CallBuilder(String callId, String name) {
            this.callId = callId;
            this.name = name;
        }

        ToolCall build() {
            return new ToolCall(callId, name, arguments.length() == 0 ? "{}" : arguments.toString());
        }
    }

    /** 一条线格式消息：连续同角色合并成一个 content 数组。 */
    private final class Wire {

        private final String role;
        private final ArrayNode content = mapper.createArrayNode();

        Wire(String role) {
            this.role = role;
        }
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
