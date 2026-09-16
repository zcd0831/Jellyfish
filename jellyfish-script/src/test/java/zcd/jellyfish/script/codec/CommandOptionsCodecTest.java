package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令候选查询编解码的单元测试。
 * <p>
 * 这条路径是<b>只读</b>的：它回答「这条命令有哪些取值」，不执行命令。因此这里也顺便钉住
 * 「请求载荷里没有执行相关字段」——一旦有人往这里塞了别的东西，说明它被当成了第二条执行路径。
 *
 * @author zcd
 */
@DisplayName("命令候选查询编解码")
class CommandOptionsCodecTest {

    /** 被测 codec。 */
    private final CommandOptionsCodec codec = new CommandOptionsCodec();

    @Test
    @DisplayName("请求只应带命令名与会话标识")
    void encodeRequest_should_carryOnlyCommandAndSession() {
        JsonNode payload = codec.encodeRequest(new CommandOptionRequest("jira", "s-1"));

        assertEquals("jira", payload.get("command").asText());
        assertEquals("s-1", payload.get("sessionId").asText());
        assertEquals(2, payload.size());
    }

    @Test
    @DisplayName("应解析出候选清单")
    void decodeResult_should_parseChoices_when_choicesArePresent() {
        CommandOptions options = codec.decodeResult(ScriptJson.tree(
                "{\"choices\":[{\"value\":\"a\",\"label\":\"A\"},{\"value\":\"b\"}]}"), "jira");

        assertEquals(2, options.getChoices().size());
        assertEquals("a", options.getChoices().get(0).getValue());
        assertEquals("b", options.getChoices().get(1).getLabel());
    }

    @Test
    @DisplayName("缺 value 的候选项应被跳过，其余仍可用")
    void decodeResult_should_skipInvalidChoice_when_valueIsMissing() {
        CommandOptions options = codec.decodeResult(
                ScriptJson.tree("{\"choices\":[{\"label\":\"无值\"},{\"value\":\"ok\"},\"字符串\"]}"), "jira");

        assertEquals(1, options.getChoices().size());
        assertEquals("ok", options.getChoices().get(0).getValue());
    }

    @Test
    @DisplayName("结果形状异常时应产出空候选，而不是抛错")
    void decodeResult_should_returnEmpty_when_resultShapeIsUnexpected() {
        assertTrue(codec.decodeResult(null, "jira").isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("\"x\""), "jira").isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), "jira").isEmpty());
    }

    @Test
    @DisplayName("候选路径应与命令执行共用同一份切片逻辑，四个字段一个不差")
    void decodeResult_should_shareChoiceParsingWithCommandCodec() {
        // CommandChoice 没有 equals，因此逐字段比对而不是比列表
        String json = "{\"choices\":[{\"value\":\"v\",\"label\":\"l\",\"description\":\"d\",\"current\":true}]}";

        CommandChoice viaOptions = codec.decodeResult(ScriptJson.tree(json), "jira").getChoices().get(0);
        CommandChoice viaCommand = new CommandCodec().decodeResult(ScriptJson.tree(json), "jira")
                .getChoices().get(0);

        assertEquals(viaCommand.getValue(), viaOptions.getValue());
        assertEquals(viaCommand.getLabel(), viaOptions.getLabel());
        assertEquals("d", viaOptions.getDescription());
        assertEquals(viaCommand.isCurrent(), viaOptions.isCurrent());
        assertTrue(viaOptions.isCurrent());
    }

    @Test
    @DisplayName("注册形态应为带路由键的唯一处理器")
    void requestTypeAndKind_should_beRoutedUnique() {
        assertEquals(CommandOptionRequest.class, codec.requestType());
        assertFalse(codec.isTypeLevel());
    }
}
