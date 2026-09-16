package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVeto;
import zcd.jellyfish.script.ScriptJson;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限拦截编解码的单元测试。
 * <p>
 * 钉住的核心是<b>只有两态</b>：{@code denied=false} 一律翻译成「无异议」，
 * 脚本无法表达「要求人工审批」——这是 {@code PermissionVeto} 类型上的约束，不是纪律。
 * 另外钉住参数原样下发：脚本要靠它判断「这次写的是哪个文件」。
 *
 * @author zcd
 */
@DisplayName("权限拦截编解码")
class PermissionCodecTest {

    /** 被测 codec。 */
    private final PermissionCodec codec = new PermissionCodec();

    @Test
    @DisplayName("请求应带 agent、工具名、参数与权限模式")
    void encodeRequest_should_carryToolArgumentsAndMode() {
        PermissionCheckRequest request = new PermissionCheckRequest("coder", "write_file",
                Collections.<String, Object>singletonMap("path", "/etc/hosts"), PermissionMode.PLAN, "s-1");

        JsonNode payload = codec.encodeRequest(request);

        assertEquals("coder", payload.get("agentId").asText());
        assertEquals("write_file", payload.get("toolName").asText());
        assertEquals("/etc/hosts", payload.get("arguments").get("path").asText());
        assertEquals("PLAN", payload.get("mode").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
    }

    @Test
    @DisplayName("denied=true 应产出带理由的拒绝")
    void decodeResult_should_deny_when_deniedIsTrue() {
        PermissionVeto veto = codec.decodeResult(
                ScriptJson.tree("{\"denied\":true,\"reason\":\"禁止写入 .env\"}"), null);

        assertTrue(veto.isDenied());
        assertEquals("禁止写入 .env", veto.getReason());
    }

    @Test
    @DisplayName("denied 缺失或为 false 都应产出无异议")
    void decodeResult_should_produceNone_when_deniedIsFalseOrAbsent() {
        assertFalse(codec.decodeResult(ScriptJson.tree("{\"denied\":false}"), null).isDenied());
        assertFalse(codec.decodeResult(ScriptJson.tree("{}"), null).isDenied());
        assertFalse(codec.decodeResult(null, null).isDenied());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(PermissionCheckRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
