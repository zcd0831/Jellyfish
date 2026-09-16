package zcd.jellyfish.script.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.script.ScriptJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话恢复编解码的单元测试。
 * <p>
 * <b>恢复的失败语义与持久化相反</b>：单个插件读不出只告警跳过，不影响其它插件，也不影响内核启动。
 * 因此这里刻意钉住「形状异常一律降级为空结果」——一个坏掉的快照不该让整段恢复失败。
 *
 * @author zcd
 */
@DisplayName("会话恢复编解码")
class SessionRestoreCodecTest {

    /** 被测 codec。 */
    private final SessionRestoreCodec codec = new SessionRestoreCodec();

    /** 一个字段完整的会话快照 JSON。 */
    private static final String SNAPSHOT = "{\"sessionId\":\"s-1\",\"createdAt\":1,\"updatedAt\":2,"
            + "\"title\":null,\"agentId\":null,\"provider\":null,\"model\":null,"
            + "\"permissionMode\":\"NORMAL\",\"messages\":[],\"usage\":null,\"compaction\":null}";

    @Test
    @DisplayName("请求应是空对象而不是 null，让脚本统一按读字段写")
    void encodeRequest_should_produceEmptyObject() {
        assertEquals(0, codec.encodeRequest(new SessionRestoreRequest()).size());
    }

    @Test
    @DisplayName("应解出会话清单")
    void decodeResult_should_parseSessions_when_sessionsArePresent() {
        SessionRestoreResult result = codec.decodeResult(
                ScriptJson.tree("{\"sessions\":[" + SNAPSHOT + "]}"), null);

        assertEquals(1, result.getSessions().size());
        assertEquals("s-1", result.getSessions().get(0).getSessionId());
    }

    @Test
    @DisplayName("坏掉的单个快照应被跳过，其余照常恢复")
    void decodeResult_should_skipBrokenSnapshot_when_oneEntryIsInvalid() {
        SessionRestoreResult result = codec.decodeResult(
                ScriptJson.tree("{\"sessions\":[{\"createdAt\":1}," + SNAPSHOT + "]}"), null);

        assertEquals(1, result.getSessions().size());
        assertEquals("s-1", result.getSessions().get(0).getSessionId());
    }

    @Test
    @DisplayName("空数组与形状异常都应产空结果，而不是抛错")
    void decodeResult_should_produceEmpty_when_noSessions() {
        assertTrue(codec.decodeResult(ScriptJson.tree("{\"sessions\":[]}"), null).getSessions().isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("{}"), null).getSessions().isEmpty());
        assertTrue(codec.decodeResult(ScriptJson.tree("\"x\""), null).getSessions().isEmpty());
        assertTrue(codec.decodeResult(null, null).getSessions().isEmpty());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(SessionRestoreRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
