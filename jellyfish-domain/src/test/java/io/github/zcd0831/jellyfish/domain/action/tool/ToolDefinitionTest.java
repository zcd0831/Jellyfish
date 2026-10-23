package io.github.zcd0831.jellyfish.domain.action.tool;

import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 JSON Schema（draft-07）校验，返回违反项而不是抛异常。
 * 规则出处：模块-行动 §7.1 规则 8、技术决策 D17、M10。
 */
class ToolDefinitionTest {

    private static final String SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"required\":[\"path\"],"
            + "\"properties\":{\"path\":{\"type\":\"string\"},\"depth\":{\"type\":\"integer\"}}"
            + "}";

    private static ToolDefinition tool(String schema) {
        return new ToolDefinition(ToolId.of("read_file"), "read_file", "读文件", new ToolSchema(schema),
                new ToolSchema("{\"type\":\"string\"}"),
                new ToolSource(SourceType.INTERNAL, "read_file"), SideEffectLevel.READ_ONLY, 5000,
                ToolStatus.ACTIVE);
    }

    @Test
    void 合法入参返回空清单() {
        Map<String, Object> args = new HashMap<>();
        args.put("path", "/tmp/a");

        assertTrue(tool(SCHEMA).validateArgs(args).isEmpty());
    }

    @Test
    void 缺必填返回违反项() {
        List<String> violations = tool(SCHEMA).validateArgs(new HashMap<String, Object>());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("path"));
    }

    @Test
    void 类型不符返回违反项() {
        Map<String, Object> args = new HashMap<>();
        args.put("path", 42);

        assertEquals(1, tool(SCHEMA).validateArgs(args).size());
    }

    @Test
    void schema本身不合法视为定义损坏() {
        List<String> violations = tool("{不是 JSON").validateArgs(new HashMap<String, Object>());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("inputSchema 无法解析"));
    }

    @Test
    void 缺schema直接判为不可用() {
        List<String> violations = tool("").validateArgs(new HashMap<String, Object>());

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("缺少 inputSchema"));
    }
}
