package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.script.ScriptJson;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化编解码的单元测试。
 * <p>
 * 快照直接复用 api 的值类型序列化，因此这里最有价值的一条是<b>字段名往返</b>：
 * 脚本读到的字段名必须与 Java 插件看到的一致，否则「脚本写得出、换个插件读不回」这类问题
 * 只会在真实落盘之后才暴露。
 *
 * @author zcd
 */
@DisplayName("会话持久化编解码")
class SessionPersistCodecTest {

    /** 被测 codec。 */
    private final SessionPersistCodec codec = new SessionPersistCodec();

    @Test
    @DisplayName("请求应把快照按 api 值类型的字段名写出")
    void encodeRequest_should_writeSnapshotFields_when_snapshotIsGiven() {
        SessionSnapshot snapshot = SessionSnapshot.of("s-1", 10L, 20L, "标题", "coder",
                "openai", "gpt-4o", PermissionMode.NORMAL, Collections.emptyList(), null);

        JsonNode payload = codec.encodeRequest(new SessionPersistRequest(snapshot));
        JsonNode node = payload.get("snapshot");

        assertEquals("s-1", node.get("sessionId").asText());
        assertEquals(10L, node.get("createdAt").asLong());
        assertEquals("标题", node.get("title").asText());
        assertEquals("coder", node.get("agentId").asText());
        assertEquals("NORMAL", node.get("permissionMode").asText());
        assertTrue(node.get("messages").isArray());
    }

    @Test
    @DisplayName("写入的快照应能被 api 类型原样读回")
    void encodeRequest_should_roundTripThroughSnapshotType() {
        SessionSnapshot snapshot = SessionSnapshot.of("s-1", 1L, 2L, null, null, null, null,
                PermissionMode.PLAN, Arrays.asList(SessionMessageSnapshot.of(
                        "m-1", 3L, "user", "你好", null, null, Collections.emptyList(), null)), null);

        JsonNode node = codec.encodeRequest(new SessionPersistRequest(snapshot)).get("snapshot");

        assertEquals(snapshot.toString(),
                ScriptJson.treeToValue(node, SessionSnapshot.class).toString());
    }

    @Test
    @DisplayName("结果类型是 Void，解码应返回 null")
    void decodeResult_should_returnNull_when_resultTypeIsVoid() {
        assertNull(codec.decodeResult(null, null));
        assertEquals(Void.class, new SessionPersistRequest(
                SessionSnapshot.of("s", 0L, 0L, null, null, null, null, PermissionMode.NORMAL,
                        Collections.emptyList(), null)).getResultType());
    }

    @Test
    @DisplayName("注册形态应为类型级贡献")
    void requestTypeAndKind_should_beTypeLevel() {
        assertEquals(SessionPersistRequest.class, codec.requestType());
        assertTrue(codec.isTypeLevel());
    }
}
