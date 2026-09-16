package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.script.ScriptJson;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 具名命令编解码的单元测试。
 * <p>
 * 重点是<b>三态如实回传</b>：{@code UNKNOWN} 与 {@code ERROR} 对用户是两种不同处置，
 * 一个混成另一个，外壳就会在「命令不存在」时只报失败，或在「命令报错」时提示去敲 {@code /help}。
 *
 * @author zcd
 */
@DisplayName("具名命令编解码")
class CommandCodecTest {

    /** 被测 codec。 */
    private final CommandCodec codec = new CommandCodec();

    @Test
    @DisplayName("请求应同时带上切分结果与原文，脚本不需要自己拆命令行")
    void encodeRequest_should_carryBothTokensAndRaw() {
        JsonNode payload = codec.encodeRequest(
                new CommandRequest("jira", new CommandArguments(Arrays.asList("PROJ-1", "fix"), "PROJ-1 fix"), "s-1"));

        assertEquals("jira", payload.get("command").asText());
        assertEquals(Arrays.asList("PROJ-1", "fix"), ScriptJson.read(payload.get("arguments").get("tokens").toString(),
                new TypeReference<List<String>>() {
                }));
        assertEquals("PROJ-1 fix", payload.get("arguments").get("raw").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
    }

    @Test
    @DisplayName("OK 且无候选时应产出普通已执行结果")
    void decodeResult_should_returnOk_when_kindIsOkWithoutChoices() {
        CommandResult result = codec.decodeResult(
                ScriptJson.tree("{\"kind\":\"OK\",\"output\":\"已更新\"}"), "jira");

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("已更新", result.getOutput());
        assertFalse(result.hasChoices());
    }

    @Test
    @DisplayName("OK 且带候选时应产出带候选的结果，供外壳渲染选择页")
    void decodeResult_should_returnChoices_when_kindIsOkWithChoices() {
        CommandResult result = codec.decodeResult(ScriptJson.tree(
                "{\"kind\":\"OK\",\"output\":\"请选择\",\"choices\":["
                        + "{\"value\":\"PROJ-1\",\"label\":\"PROJ-1 修复登录\",\"current\":true},"
                        + "{\"value\":\"PROJ-2\"}]}"), "jira");

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals(2, result.getChoices().size());
        assertEquals("PROJ-1", result.getChoices().get(0).getValue());
        assertEquals("PROJ-1 修复登录", result.getChoices().get(0).getLabel());
        assertTrue(result.getChoices().get(0).isCurrent());
        // label 缺失时回退为 value，避免选择页出现空白行
        assertEquals("PROJ-2", result.getChoices().get(1).getLabel());
    }

    @Test
    @DisplayName("ERROR 与 UNKNOWN 必须区分开")
    void decodeResult_should_distinguishErrorFromUnknown() {
        assertEquals(CommandResult.Kind.ERROR,
                codec.decodeResult(ScriptJson.tree("{\"kind\":\"ERROR\",\"output\":\"炸了\"}"), "jira").getKind());
        assertEquals(CommandResult.Kind.UNKNOWN,
                codec.decodeResult(ScriptJson.tree("{\"kind\":\"UNKNOWN\"}"), "jira").getKind());
    }

    @Test
    @DisplayName("kind 缺失时按 OK 处理，只给 output 是常见写法")
    void decodeResult_should_defaultToOk_when_kindIsAbsent() {
        CommandResult result = codec.decodeResult(ScriptJson.tree("{\"output\":\"done\"}"), "jira");

        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("done", result.getOutput());
    }

    @Test
    @DisplayName("kind 非法应报错，不静默降级")
    void decodeResult_should_throwJellyfishException_when_kindIsUnknownValue() {
        assertThrows(JellyfishException.class,
                () -> codec.decodeResult(ScriptJson.tree("{\"kind\":\"MAYBE\"}"), "jira"));
    }

    @Test
    @DisplayName("结果不是对象时应产出无输出的已执行结果")
    void decodeResult_should_returnEmptyOk_when_resultIsNotObject() {
        assertEquals(CommandResult.Kind.OK, codec.decodeResult(null, "jira").getKind());
        assertNull(codec.decodeResult(null, "jira").getOutput());
    }

    @Test
    @DisplayName("注册形态应为带路由键的唯一处理器")
    void requestTypeAndKind_should_beRoutedUnique() {
        assertEquals(CommandRequest.class, codec.requestType());
        assertFalse(codec.isTypeLevel());
    }
}
