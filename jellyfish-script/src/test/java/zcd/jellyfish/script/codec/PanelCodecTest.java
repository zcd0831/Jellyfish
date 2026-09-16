package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板贡献编解码的单元测试。
 * <p>
 * 两处宽容是重点：落位名不认识就按「未指定」处理、强调名不认识就退回 {@code NORMAL}。
 * 两者都遵循 api 里对应枚举的既有约定——宁可样式不对，也不要整块面板消失。
 *
 * @author zcd
 */
@DisplayName("面板贡献编解码")
class PanelCodecTest {

    /** 被测 codec。 */
    private final PanelCodec codec = new PanelCodec();

    @Test
    @DisplayName("请求应带会话标识")
    void encodeRequest_should_carrySessionId() {
        assertEquals("s-1", codec.encodeRequest(new PanelContributionRequest("s-1")).get("sessionId").asText());
    }

    @Test
    @DisplayName("应解出标题、落位与文本段")
    void decodeResult_should_readLinesAndRegion_when_payloadIsComplete() {
        PanelContribution panel = codec.decodeResult(ScriptJson.tree(
                "{\"title\":\"Jira\",\"region\":\"DOCK\",\"lines\":[{\"segments\":["
                        + "{\"text\":\"PROJ-1 \",\"emphasis\":\"NORMAL\"},{\"text\":\"●\",\"emphasis\":\"ACCENT\"}]}]}"),
                null);

        assertEquals("Jira", panel.getTitle());
        assertSame(UiRegion.DOCK, panel.getPreferredRegion());
        assertEquals(1, panel.getLines().size());
        assertEquals("PROJ-1 ●", panel.getLines().get(0).text());
        assertEquals(UiEmphasis.ACCENT, panel.getLines().get(0).getSegments().get(1).getEmphasis());
    }

    @Test
    @DisplayName("整行字符串简写应被接受")
    void decodeResult_should_acceptPlainStringLine_when_authorSkipsSegments() {
        PanelContribution panel = codec.decodeResult(ScriptJson.tree("{\"lines\":[\"第一行\",\"第二行\"]}"), null);

        assertEquals(2, panel.getLines().size());
        assertEquals("第一行", panel.getLines().get(0).text());
    }

    @Test
    @DisplayName("落位名不认识时应按未指定处理，而不是报错")
    void decodeResult_should_ignoreRegion_when_regionNameIsUnknown() {
        PanelContribution panel = codec.decodeResult(
                ScriptJson.tree("{\"region\":\"BOTTOM\",\"lines\":[\"x\"]}"), null);

        assertNull(panel.getPreferredRegion());
        assertEquals(1, panel.getLines().size());
    }

    @Test
    @DisplayName("强调名不认识时应退回 NORMAL，而不是丢掉这段文本")
    void decodeResult_should_fallBackToNormal_when_emphasisNameIsUnknown() {
        PanelContribution panel = codec.decodeResult(ScriptJson.tree(
                "{\"lines\":[{\"segments\":[{\"text\":\"x\",\"emphasis\":\"GLOW\"}]}]}"), null);

        assertEquals("x", panel.getLines().get(0).text());
        assertEquals(UiEmphasis.NORMAL, panel.getLines().get(0).getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("无内容时应产出空面板")
    void decodeResult_should_produceEmpty_when_noLines() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"title\":\"x\"}"), null).isEmpty());
        assertTrue(codec.decodeResult(null, null).isEmpty());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(PanelContributionRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
