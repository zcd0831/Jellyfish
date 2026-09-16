package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词贡献编解码的单元测试。
 * <p>
 * 它只进 system prompt、不追加进消息列表，因此这里除了往返之外还钉住「空贡献是正当返回值」——
 * 一旦把空贡献当成错误，插件就会被迫每轮都塞点东西进去，而 system prompt 每轮都在烧 token。
 *
 * @author zcd
 */
@DisplayName("提示词贡献编解码")
class PromptCodecTest {

    /** 被测 codec。 */
    private final PromptCodec codec = new PromptCodec();

    @Test
    @DisplayName("请求应带会话标识")
    void encodeRequest_should_carrySessionId() {
        assertEquals("s-1", codec.encodeRequest(new PromptContributionRequest("s-1")).get("sessionId").asText());
    }

    @Test
    @DisplayName("应解出贡献文本")
    void decodeResult_should_readText_when_textIsPresent() {
        PromptContribution contribution = codec.decodeResult(ScriptJson.tree("{\"text\":\"待办 2/5\"}"), null);

        assertEquals("待办 2/5", contribution.getText());
    }

    @Test
    @DisplayName("文本缺失或为 null 都应产出空贡献")
    void decodeResult_should_produceEmpty_when_textIsAbsentOrNull() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"text\":null}"), null).isEmpty());
        assertTrue(codec.decodeResult(null, null).isEmpty());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(PromptContributionRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
