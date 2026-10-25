package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次状态转移：追加流水 + 替换聚合，提交后落盘。
 * 规则出处：模块-应用层 §8.8、技术决策 D07。
 */
class FileCommitScopeTest {

    @TempDir
    Path tmp;

    private DataDirectory dir() {
        return DataDirectory.of(tmp.toString());
    }

    @Test
    void 提交后既写入流水也替换会话头() {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m"), 1L, null);
        Record record = new Record(RecordId.of("r1"), RecordRole.USER, "你好", null, 1, 2L);
        session.appendRecord(RecordRole.USER, "你好", null, 2L);
        session.retitle("t");

        FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
        scope.stageAppend(SessionId.of("s1"), record);
        scope.stageReplace(session);
        scope.commit();

        assertTrue(tmp.resolve("sessions/s1/meta.json").toFile().exists());
        assertTrue(tmp.resolve("sessions/s1/records-0001.jsonl").toFile().exists());
        assertEquals("t", new FileSessionRepository(dir(), new JsonCodec()).load(SessionId.of("s1")).title());
    }

    @Test
    void 流水的全部字段都经生产写路径落盘() {
        Record record = new Record(RecordId.of("r1"), RecordRole.TOOL_REQUEST,
                "{\"callId\":\"c1\",\"name\":\"read_file\",\"arguments\":\"{}\"}", "内部推理", "t1",
                "m1", null, 0, 1, 2L);
        FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
        scope.stageAppend(SessionId.of("s1"), record);
        scope.commit();

        Record loaded = new FileRecordStore(dir(), new JsonCodec()).last(SessionId.of("s1"), 1).get(0);
        assertEquals("内部推理", loaded.reasoning(), "reasoning 丢失会让思维模式回放 400");
        assertEquals("t1", loaded.turnId(), "turnId 丢失会破坏一响应一消息的重建");
        assertEquals("m1", loaded.producerModelId(), "产者丢失会让旧模型的 reasoning 被错发");
        assertEquals(0, loaded.summaryCoversUpTo());
    }

    @Test
    void 摘要覆盖边界也经生产写路径落盘() {
        Record summary = new Record(RecordId.of("r1"), RecordRole.SUMMARY, "摘要正文", null, null, null, null,
                7, 1, 2L);
        FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
        scope.stageAppend(SessionId.of("s1"), summary);
        scope.commit();

        Record loaded = new FileRecordStore(dir(), new JsonCodec()).last(SessionId.of("s1"), 1).get(0);
        assertEquals(7, loaded.summaryCoversUpTo(), "覆盖边界丢失会让重启后的折叠失效");
    }

    @Test
    void 不支持的类型立即失败而不是静默丢写() {
        FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
        scope.stageReplace("不是聚合");

        assertThrows(UnsupportedOperationException.class, scope::commit);
    }
}
