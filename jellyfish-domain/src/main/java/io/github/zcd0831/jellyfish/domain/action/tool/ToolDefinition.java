package io.github.zcd0831.jellyfish.domain.action.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工具聚合根。规则出处：05-action §5.4.1、§5.8.1。 */
public class ToolDefinition {

    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolId id;
    private final String name;
    private final ToolSource source;
    private final int timeoutMillis;

    private String description;
    private ToolSchema inputSchema;
    private ToolSchema outputSchema;
    private SideEffectLevel sideEffect;
    private ToolStatus status;

    public ToolDefinition(ToolId id, String name, String description, ToolSchema inputSchema,
                          ToolSchema outputSchema, ToolSource source, SideEffectLevel sideEffect,
                          int timeoutMillis, ToolStatus status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.source = source;
        this.sideEffect = sideEffect;
        this.timeoutMillis = timeoutMillis;
        this.status = status;
    }

    public void updateMetadata(String description, ToolSchema inputSchema) {
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /** 规则出处：05-action §5.8.1 规则 1（只有 ACTIVE 进入快照、可被调用）。 */
    public void activate() {
        this.status = ToolStatus.ACTIVE;
    }

    public void deprecate() {
        this.status = ToolStatus.DEPRECATED;
    }

    public boolean isActive() {
        return status == ToolStatus.ACTIVE;
    }

    /**
     * 完整 JSON Schema 校验；返回违反项，空 = 通过。
     * 校验失败是预期内结果，所以返回清单而不是抛异常（05-action §5.8.1 规则 8）。
     */
    public List<String> validateArgs(Map<String, Object> args) {
        List<String> violations = new ArrayList<>();
        if (inputSchema == null || inputSchema.getJsonSchemaString().trim().isEmpty()) {
            violations.add("工具缺少 inputSchema：" + id.getValue());
            return violations;
        }
        JsonNode schemaNode;
        JsonSchema schema;
        try {
            schemaNode = MAPPER.readTree(inputSchema.getJsonSchemaString());
            schema = SCHEMA_FACTORY.getSchema(schemaNode);
        } catch (Exception e) {
            // schema 本身不合法 = 工具定义损坏，不是"模型给错参数"
            violations.add("inputSchema 无法解析：" + id.getValue() + "（" + e.getMessage() + "）");
            return violations;
        }
        Set<ValidationMessage> messages = schema.validate(MAPPER.valueToTree(args));
        for (ValidationMessage message : messages) {
            violations.add(message.getMessage());
        }
        return violations;
    }

    /** 规则出处：05-action §5.8.1 规则 5（非只读且不在信任清单 ⇒ 执行前放行审批）。 */
    public SideEffectLevel sideEffect() {
        return sideEffect;
    }

    public ToolId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public ToolSchema inputSchema() {
        return inputSchema;
    }

    public ToolSchema outputSchema() {
        return outputSchema;
    }

    public ToolSource source() {
        return source;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public ToolStatus status() {
        return status;
    }
}
