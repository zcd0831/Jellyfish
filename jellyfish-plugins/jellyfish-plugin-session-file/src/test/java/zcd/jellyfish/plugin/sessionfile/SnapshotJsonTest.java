package zcd.jellyfish.plugin.sessionfile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SnapshotJson} 的单元测试。
 * <p>
 * <b>这是最要紧的一组测试</b>：快照类型是「全字段构造器 + 无 setter」的不可变对象，Jackson 只能靠
 * 构造器参数名反序列化（依赖编译期 {@code -parameters} 与 {@code ParameterNamesModule}）。
 * 这条链路一旦断开，表现是「文件写出来了，重启后读不回来」——只有往返比对能提前发现。
 *
 * @author zcd
 */
@DisplayName("会话快照 JSON 读写")
class SnapshotJsonTest {

    @Test
    @DisplayName("往返一圈后 JSON 必须逐字相等")
    void read_should_roundTrip_when_snapshotComplete() {
        SessionSnapshot original = TestSnapshots.full("session-1");

        String first = SnapshotJson.write(original);
        String second = SnapshotJson.write(SnapshotJson.read(first, "test"));

        assertEquals(first, second);
    }

    @Test
    @DisplayName("反序列化后每个字段都要对得上，而不是只剩一个空壳")
    void read_should_restoreEveryField() {
        SessionSnapshot restored = SnapshotJson.read(SnapshotJson.write(TestSnapshots.full("session-1")), "test");

        assertEquals("session-1", restored.getSessionId());
        assertEquals(100L, restored.getCreatedAt());
        assertEquals(200L, restored.getUpdatedAt());
        assertEquals("标题", restored.getTitle());
        assertEquals("coder", restored.getAgentId());
        assertEquals("openai", restored.getProvider());
        assertEquals("gpt-4o", restored.getModel());
        assertEquals(PermissionMode.PLAN, restored.getPermissionMode());
        assertEquals(4, restored.getMessages().size());
        assertEquals(2, restored.getTodos().size());
        assertEquals("call-1", restored.getMessages().get(1).getToolCalls().get(0).getId());
        assertEquals("{\"path\":\"a.txt\"}", restored.getMessages().get(1).getToolCalls().get(0).getArguments());
        assertEquals(15, restored.getMessages().get(1).getUsage().getTotalTokens());
        assertTrue(restored.getTodos().get(0).isDone());
        assertEquals(4L, restored.getUsage().getLlmCalls());
    }

    @Test
    @DisplayName("字段全空的会话也要能往返")
    void read_should_roundTrip_when_snapshotMinimal() {
        SessionSnapshot original = TestSnapshots.minimal("session-2");

        String first = SnapshotJson.write(original);

        assertEquals(first, SnapshotJson.write(SnapshotJson.read(first, "test")));
    }

    @Test
    @DisplayName("多出来的未知字段必须被忽略：旧版本读完新版本写的文件不该整段丢掉")
    void read_should_ignoreUnknownFields() {
        String json = SnapshotJson.write(TestSnapshots.minimal("session-3"));
        String withExtra = json.replaceFirst("\\{", "{\n  \"futureField\": \"x\",");

        assertEquals("session-3", SnapshotJson.read(withExtra, "test").getSessionId());
    }

    @Test
    @DisplayName("坏 JSON 要报出可读原因，而不是抛 Unknown")
    void read_should_fail_when_jsonBroken() {
        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> SnapshotJson.read("{不是 JSON", "bad.json"));

        assertTrue(failure.getMessage().contains("bad.json"), failure.getMessage());
    }

    @Test
    @DisplayName("缺少必需字段要报错，不能静默产出一个字段全空的会话")
    void read_should_fail_when_requiredFieldMissing() {
        assertThrows(JellyfishException.class, () -> SnapshotJson.read("{\"title\":\"没有 id\"}", "bad.json"));
    }
}
