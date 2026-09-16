package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.SessionDeleteRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话删除编解码的单元测试。
 * <p>
 * 载荷只有会话标识，因此这里额外钉住「结果类型是 Void」——删除是插件的清理时机，
 * 它不该、也无法通过返回值影响「删除会话」这个动作本身。
 *
 * @author zcd
 */
@DisplayName("会话删除编解码")
class SessionDeleteCodecTest {

    /** 被测 codec。 */
    private final SessionDeleteCodec codec = new SessionDeleteCodec();

    @Test
    @DisplayName("请求应只带会话标识")
    void encodeRequest_should_carryOnlySessionId() {
        assertEquals("s-1", codec.encodeRequest(new SessionDeleteRequest("s-1")).get("sessionId").asText());
        assertEquals(1, codec.encodeRequest(new SessionDeleteRequest("s-1")).size());
    }

    @Test
    @DisplayName("结果类型是 Void，解码应返回 null")
    void decodeResult_should_returnNull_when_resultTypeIsVoid() {
        assertNull(codec.decodeResult(null, null));
        assertEquals(Void.class, new SessionDeleteRequest("s-1").getResultType());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(SessionDeleteRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
