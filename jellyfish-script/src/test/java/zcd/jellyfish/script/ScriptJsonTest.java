package zcd.jellyfish.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨语言运行时序列化封装的单元测试。
 * <p>
 * 重点不在「Jackson 能不能用」，而在本类<b>声明的两条协议口径</b>：写全 {@code null} 字段、
 * 忽略未知字段。这两条一旦被改动，脚本侧的行为会静默变化（缺省与显式 {@code null} 混为一谈、
 * 或者内核升级后旧脚本集体调用失败），因此各用一个用例钉住。
 *
 * @author zcd
 */
@DisplayName("跨语言运行时序列化封装")
class ScriptJsonTest {

    @Test
    @DisplayName("序列化应写出值为 null 的字段")
    void write_should_includeNullFields_when_fieldValueIsNull() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", null);
        payload.put("tool", "read_file");

        String json = ScriptJson.write(payload);

        assertTrue(json.contains("\"sessionId\":null"), json);
        assertTrue(json.contains("\"tool\":\"read_file\""), json);
    }

    @Test
    @DisplayName("反序列化应忽略类里不存在的字段")
    void read_should_ignoreUnknownField_when_jsonHasExtraField() {
        String json = "{\"tool\":\"read_file\",\"unknown\":{\"deep\":[1,2]}}";

        Payload payload = ScriptJson.read(json, Payload.class);

        assertEquals("read_file", payload.tool);
    }

    @Test
    @DisplayName("反序列化泛型类型应保留元素类型")
    void read_should_preserveElementType_when_typeReferenceIsUsed() {
        String json = "{\"a\":{\"tool\":\"one\"},\"b\":{\"tool\":\"two\"}}";

        Map<String, Payload> payloads = ScriptJson.read(json, new TypeReference<Map<String, Payload>>() {
        });

        assertEquals("one", payloads.get("a").tool);
        assertEquals("two", payloads.get("b").tool);
    }

    @Test
    @DisplayName("JSON 非法时应抛统一异常")
    void read_should_throwJellyfishException_when_jsonIsMalformed() {
        assertThrows(JellyfishException.class, () -> ScriptJson.read("{oops", Payload.class));
    }

    @Test
    @DisplayName("对象无法序列化时应抛统一异常，而不是泄漏 Jackson 异常")
    void write_should_throwJellyfishException_when_valueIsNotSerializable() {
        assertThrows(JellyfishException.class, () -> ScriptJson.write(new Object()));
    }

    @Test
    @DisplayName("树解析应保留原始结构，供协议分发先看形状")
    void tree_should_keepStructure_when_jsonIsValid() {
        JsonNode node = ScriptJson.tree("{\"id\":3,\"method\":\"invoke\"}");

        assertTrue(node.isObject());
        assertEquals(3, node.get("id").asInt());
        assertEquals("invoke", node.get("method").asText());
    }

    @Test
    @DisplayName("对象转树应产出可继续读取的节点")
    void treeOf_should_convertObject_when_valueIsSerializable() {
        Payload payload = new Payload();
        payload.tool = "echo";

        ObjectNode node = (ObjectNode) ScriptJson.treeOf(payload);

        assertEquals("echo", node.get("tool").asText());
    }

    @Test
    @DisplayName("含 null 元素的列表应原样写出，不做压缩")
    void write_should_keepNullElements_when_listContainsNull() {
        List<String> values = Arrays.asList("a", null, "c");

        assertEquals("[\"a\",null,\"c\"]", ScriptJson.write(values));
    }

    @Test
    @DisplayName("序列化 null 应产出 JSON null 而不是抛错")
    void write_should_produceJsonNull_when_valueIsNull() {
        assertEquals("null", ScriptJson.write(null));
    }

    @Test
    @DisplayName("树解析失败不应把 Jackson 异常泄漏到调用方")
    void tree_should_throwJellyfishException_when_jsonIsMalformed() {
        JellyfishException failure = assertThrows(JellyfishException.class, () -> ScriptJson.tree("[1,"));

        assertFalse(failure.getMessage().isEmpty());
    }

    /**
     * 测试用载荷：刻意用公开字段 + 隐式无参构造器，这样它既不依赖 {@code -parameters}，
     * 也不会因为多出一个构造器而触发 Jackson 的「创建者冲突」。
     *
     * @author zcd
     */
    static final class Payload {

        /** 工具名。 */
        public String tool;
    }
}
