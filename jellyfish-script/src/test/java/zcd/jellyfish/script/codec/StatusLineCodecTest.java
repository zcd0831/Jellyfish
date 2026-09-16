package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态栏贡献编解码的单元测试。
 * <p>
 * 状态栏每一列都很贵，因此这里额外钉住「空白文本归一为空贡献」：一个纯空格片段会在拼接处
 * 留下双倍间隔，而作者本意是「这次没话说」。
 *
 * @author zcd
 */
@DisplayName("状态栏贡献编解码")
class StatusLineCodecTest {

    /** 被测 codec。 */
    private final StatusLineCodec codec = new StatusLineCodec();

    @Test
    @DisplayName("请求应带会话标识")
    void encodeRequest_should_carrySessionId() {
        assertEquals("s-1",
                codec.encodeRequest(new StatusLineContributionRequest("s-1")).get("sessionId").asText());
    }

    @Test
    @DisplayName("应解出片段文本")
    void decodeResult_should_readText_when_textIsPresent() {
        assertEquals("待办 2/5", codec.decodeResult(ScriptJson.tree("{\"text\":\"待办 2/5\"}"), null).getText());
    }

    @Test
    @DisplayName("空白文本应归一为空贡献")
    void decodeResult_should_produceEmpty_when_textIsBlank() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"text\":\"   \"}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
        assertTrue(codec.decodeResult(null, null).isEmpty());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(StatusLineContributionRequest.class, codec.requestType());
        assertEquals(StatusLineContribution.class, new StatusLineContributionRequest("s").getResultType());
        assertTrue(codec.isTypeLevel());
    }
}
