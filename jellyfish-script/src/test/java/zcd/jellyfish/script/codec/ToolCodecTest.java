package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用编解码的单元测试。
 * <p>
 * 核心是 <b>{@code output} 的翻译规则必须与内核一致</b>：字符串原样回灌、结构化值交给内核序列化。
 * 若这里一律转成文本，脚本返回结构化数据时会被二次转义，模型看到的是转义后的字符串——
 * 而这类错误在「返回的就是字符串」的用例里完全看不出来，所以每种 JSON 类型各钉一个。
 *
 * @author zcd
 */
@DisplayName("工具调用编解码")
class ToolCodecTest {

    /** 被测 codec。 */
    private final ToolCodec codec = new ToolCodec();

    @Test
    @DisplayName("请求应包含工具名、参数与会话标识")
    void encodeRequest_should_carryToolNameArgumentsAndSession() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("key", "PROJ-1");

        JsonNode payload = codec.encodeRequest(
                new ToolCallRequest("jira_issue", arguments, "s-1"));

        assertEquals("jira_issue", payload.get("tool").asText());
        assertEquals("PROJ-1", payload.get("arguments").get("key").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
    }

    @Test
    @DisplayName("字符串输出应原样保留为字符串，而不是被再次转义")
    void decodeResult_should_keepStringOutput_when_outputIsTextual() {
        ToolCallResult result = codec.decodeResult(ScriptJson.tree("{\"output\":\"hello\"}"), "echo");

        assertEquals("echo", result.getToolName());
        assertEquals("hello", result.getOutput());
    }

    @Test
    @DisplayName("结构化输出应还原成集合，交给内核去序列化")
    void decodeResult_should_restoreStructuredOutput_when_outputIsObject() {
        ToolCallResult result = codec.decodeResult(
                ScriptJson.tree("{\"output\":{\"files\":[\"a\",\"b\"],\"count\":2}}"), "list");

        assertTrue(result.getOutput() instanceof Map, String.valueOf(result.getOutput()));
        Map<?, ?> output = (Map<?, ?>) result.getOutput();
        assertEquals(Arrays.asList("a", "b"), output.get("files"));
        assertEquals(2, output.get("count"));
    }

    @Test
    @DisplayName("数字与布尔输出应还原成对应标量类型")
    void decodeResult_should_restoreScalars_when_outputIsNumberOrBoolean() {
        assertEquals(42, codec.decodeResult(ScriptJson.tree("{\"output\":42}"), "count").getOutput());
        assertEquals(Boolean.TRUE,
                codec.decodeResult(ScriptJson.tree("{\"output\":true}"), "flag").getOutput());
    }

    @Test
    @DisplayName("output 缺失或为 null 时应产出空输出")
    void decodeResult_should_produceNullOutput_when_outputIsAbsentOrNull() {
        assertNull(codec.decodeResult(ScriptJson.tree("{}"), "echo").getOutput());
        assertNull(codec.decodeResult(ScriptJson.tree("{\"output\":null}"), "echo").getOutput());
        assertNull(codec.decodeResult(null, "echo").getOutput());
    }

    @Test
    @DisplayName("结果是裸值时应整个当成输出，作为忘记包一层时的安全网")
    void decodeResult_should_treatWholeResultAsOutput_when_resultIsNotObject() {
        assertEquals("hello", codec.decodeResult(ScriptJson.tree("\"hello\""), "echo").getOutput());
        assertEquals(Collections.singletonList(1),
                codec.decodeResult(ScriptJson.tree("[1]"), "echo").getOutput());
    }

    @Test
    @DisplayName("工具名来自路由键，不由脚本回传")
    void decodeResult_should_takeToolNameFromRouteKey_when_resultOmitsIt() {
        assertEquals("from-registry",
                codec.decodeResult(ScriptJson.tree("{\"output\":\"x\"}"), "from-registry").getToolName());
    }

    @Test
    @DisplayName("注册形态应为带路由键的唯一处理器")
    void requestTypeAndKind_should_beRoutedUnique() {
        assertEquals(ToolCallRequest.class, codec.requestType());
        assertEquals("tool", codec.typeName());
        assertTrue(!codec.isTypeLevel());
    }
}
