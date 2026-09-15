package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zcd.jellyfish.plugin.tools.ToolTestSupport.expectFailure;

/**
 * {@link ToolSchema} 的单元测试。
 * <p>
 * 重点锁住两条：单个参数的 Schema 形状（内核按 JSON Schema 的 properties 直接使用），
 * 以及「参数名 / Schema 必须成对」这条易错约定必须报错而不是静默丢弃。
 *
 * @author zcd
 */
@DisplayName("ToolSchema 参数 Schema 构造")
class ToolSchemaTest {

    @Test
    @DisplayName("字符串参数应带 string 类型与描述")
    void string_should_buildTypedSchema() {
        Map<String, Object> schema = ToolSchema.string("文件路径");

        assertEquals("string", schema.get("type"));
        assertEquals("文件路径", schema.get("description"));
    }

    @Test
    @DisplayName("整数与布尔参数类型应分别是 integer 与 boolean")
    void typedSchemas_should_useExpectedJsonTypes() {
        assertEquals("integer", ToolSchema.integer("行数").get("type"));
        assertEquals("boolean", ToolSchema.bool("是否替换全部").get("type"));
    }

    @Test
    @DisplayName("properties 应按声明顺序保留参数")
    void properties_should_preserveDeclarationOrder() {
        Map<String, Object> properties = ToolSchema.properties(
                "path", ToolSchema.string("路径"),
                "limit", ToolSchema.integer("行数"));

        assertEquals("[path, limit]", properties.keySet().toString());
        assertEquals(ToolSchema.string("路径"), properties.get("path"));
    }

    @Test
    @DisplayName("参数名与 Schema 未成对应报错")
    void properties_should_fail_when_pairIncomplete() {
        JellyfishException failure = expectFailure(
                () -> ToolSchema.properties("path", ToolSchema.string("路径"), "limit"));

        assertTrue(failure.getMessage().contains("成对"), failure.getMessage());
    }

    @Test
    @DisplayName("参数名不是字符串应报错")
    void properties_should_fail_when_nameNotString() {
        JellyfishException failure = expectFailure(
                () -> ToolSchema.properties(1, ToolSchema.string("路径")));

        assertTrue(failure.getMessage().contains("参数名"), failure.getMessage());
    }
}
