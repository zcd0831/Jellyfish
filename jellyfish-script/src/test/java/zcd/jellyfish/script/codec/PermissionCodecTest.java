package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.script.ScriptJson;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限拦截编解码的单元测试。
 * <p>
 * 钉住的核心是<b>三态里没有「放行」</b>：脚本能表达的只有无异议、要求人工审批、拒绝，
 * 因此「脚本只能收紧、不能放宽」是 {@code PermissionVerdict} 类型上的约束，不是纪律。
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
    @DisplayName("DENY 应产出带理由的拒绝")
    void decodeResult_should_deny_whenVerdictIsDeny() {
        PermissionVerdict verdict = codec.decodeResult(
                ScriptJson.tree("{\"verdict\":\"DENY\",\"reason\":\"禁止写入 .env\"}"), null);

        assertTrue(verdict.isDenied());
        assertEquals("禁止写入 .env", verdict.getReason());
    }

    @Test
    @DisplayName("ASK 应产出要求人工审批的裁定")
    void decodeResult_should_ask_whenVerdictIsAsk() {
        PermissionVerdict verdict = codec.decodeResult(
                ScriptJson.tree("{\"verdict\":\"ASK\",\"reason\":\"写类命令需要人看一眼\"}"), null);

        assertTrue(verdict.isAsk());
        assertFalse(verdict.isDenied());
        assertEquals("写类命令需要人看一眼", verdict.getReason());
    }

    @Test
    @DisplayName("ABSTAIN、缺失或 null 载荷都应产出无异议")
    void decodeResult_should_abstain_whenVerdictIsAbstainOrAbsent() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"verdict\":\"ABSTAIN\"}"), null).isAbstain());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isAbstain());
        assertTrue(codec.decodeResult(null, null).isAbstain());
    }

    @Test
    @DisplayName("裁定大小写不敏感")
    void decodeResult_should_ignoreVerdictCase() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"verdict\":\"ask\"}"), null).isAsk());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"verdict\":\" deny \"}"), null).isDenied());
    }

    @Test
    @DisplayName("未知裁定应报错而不是静默按无异议处理")
    void decodeResult_should_throw_whenVerdictUnknown() {
        // 静默降级会让一道本该有人看的调用直接放行，而写错拼写的脚本作者完全不知道
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> codec.decodeResult(ScriptJson.tree("{\"verdict\":\"ALLOW\"}"), "write_file"));

        assertTrue(error.getMessage().contains("ALLOW"), error.getMessage());
        assertTrue(error.getMessage().contains("write_file"), error.getMessage());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(PermissionCheckRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
