package zcd.jellyfish.script.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通信协议帧编解码的单元测试。
 * <p>
 * 这里刻意钉住两件容易被当成「宽松一点更好」的事：<b>解析失败必须返回 {@code null} 而不是抛异常</b>
 * （脚本误打印一行日志不该让工具调用失败），以及 <b>id 只接受整数</b>
 * （允许字符串 id 会让配对表出现两种键，而失败表现是「调用卡到超时」，现场离原因很远）。
 *
 * @author zcd
 */
@DisplayName("脚本通信协议")
class ScriptProtocolTest {

    @Test
    @DisplayName("请求帧应带 id、方法名与参数")
    void request_should_encodeIdMethodAndParams() {
        JsonNode params = ScriptJson.tree("{\"script\":\"jira\"}");

        ScriptProtocol.Message parsed = ScriptProtocol.parse(ScriptProtocol.request(7, "invoke", params));

        assertNotNull(parsed);
        assertEquals(Long.valueOf(7), parsed.id());
        assertEquals("invoke", parsed.method());
        assertEquals("jira", parsed.paramText("script"));
        assertFalse(parsed.isResponse());
        assertTrue(parsed.needsResponse());
    }

    @Test
    @DisplayName("通知帧不应带 id，也不需要应答")
    void notification_should_haveNoId() {
        ScriptProtocol.Message parsed =
                ScriptProtocol.parse(ScriptProtocol.notification("event", ScriptJson.tree("{}")));

        assertNotNull(parsed);
        assertNull(parsed.id());
        assertEquals("event", parsed.method());
        assertFalse(parsed.needsResponse());
    }

    @Test
    @DisplayName("成功应答应解析出结果载荷")
    void parse_should_readResult_when_responseSucceeds() {
        ScriptProtocol.Message parsed = ScriptProtocol.parse(ScriptProtocol.response(3, ScriptJson.tree("{\"output\":\"ok\"}")));

        assertNotNull(parsed);
        assertTrue(parsed.isResponse());
        assertFalse(parsed.isFailure());
        assertEquals("ok", parsed.result().get("output").asText());
    }

    @Test
    @DisplayName("失败应答应解析出错误码与描述")
    void parse_should_readError_when_responseFails() {
        ScriptProtocol.Message parsed =
                ScriptProtocol.parse(ScriptProtocol.errorResponse(3, ScriptProtocol.CODE_SCRIPT_FAILURE, "脚本炸了"));

        assertNotNull(parsed);
        assertTrue(parsed.isFailure());
        assertEquals(ScriptProtocol.CODE_SCRIPT_FAILURE, parsed.errorCode());
        assertEquals("脚本炸了", parsed.errorMessage());
    }

    @Test
    @DisplayName("错误对象缺 code 时应退化为内部错误码，而不是当成成功")
    void parse_should_degradeToInternalCode_when_errorHasNoCode() {
        ScriptProtocol.Message parsed =
                ScriptProtocol.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"x\"}}");

        assertNotNull(parsed);
        assertTrue(parsed.isFailure());
        assertEquals(ScriptProtocol.CODE_INTERNAL, parsed.errorCode());
    }

    @Test
    @DisplayName("脏行应被宽容丢弃，绝不抛异常")
    void parse_should_returnNull_when_lineIsNotAUsableFrame() {
        assertNull(ScriptProtocol.parse(null));
        assertNull(ScriptProtocol.parse(""));
        assertNull(ScriptProtocol.parse("   "));
        assertNull(ScriptProtocol.parse("这是一行误打印的调试信息"));
        assertNull(ScriptProtocol.parse("[1,2,3]"));
        assertNull(ScriptProtocol.parse("{\"jsonrpc\":\"2.0\"}"));
        // 字符串 id 不被接受：配对表里出现两种键会让「调用卡到超时」，而不是报错
        assertNull(ScriptProtocol.parse("{\"id\":\"7\",\"result\":{}}"));
    }

    @Test
    @DisplayName("结果缺省时的成功应答应解析为 null 结果")
    void parse_should_yieldNullResult_when_resultFieldIsAbsent() {
        ScriptProtocol.Message parsed = ScriptProtocol.parse("{\"jsonrpc\":\"2.0\",\"id\":5}");

        assertNotNull(parsed);
        assertTrue(parsed.isResponse());
        assertNull(parsed.result());
    }

    @Test
    @DisplayName("参数取文本时应拒绝空白串")
    void paramText_should_rejectBlankValues() {
        ScriptProtocol.Message parsed =
                ScriptProtocol.parse("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":{\"script\":\"  \"}}");

        assertNotNull(parsed);
        assertNull(parsed.paramText("script"));
        assertNull(parsed.paramText("missing"));
    }
}
