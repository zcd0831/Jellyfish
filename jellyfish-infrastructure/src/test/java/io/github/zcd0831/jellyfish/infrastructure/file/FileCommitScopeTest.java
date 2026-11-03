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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次状态转移：追加流水 + 替换聚合，提交后落盘。
 * 规则出处：08-application §8.8。
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

    @Test
    void 提交按流水到聚合的顺序落盘且每阶段收尾目录() {
        Session session = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m"), 1L, null);
        Record record = new Record(RecordId.of("r1"), RecordRole.USER, "你好", null, 1, 2L);

        List<String> events = new ArrayList<String>();
        AtomicFiles.useSyncForTest(new AtomicFiles.DiskSync() {
            @Override
            public void file(java.nio.file.Path path) {
                events.add("file:" + path.getFileName());
            }

            @Override
            public void directory(java.nio.file.Path path) {
                events.add("dir:" + path.getFileName());
            }
        });
        try {
            FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
            scope.stageAppend(SessionId.of("s1"), record);
            scope.stageReplace(session);
            scope.commit();
        } finally {
            AtomicFiles.useSyncForTest(null);
        }

        // 规则出处：09-infrastructure §9.6「② 所有 stageAppend → 追加；全部写完后 fsync 记录文件」「④ 每阶段末尾 fsync 所在目录」
        assertEquals(java.util.Arrays.asList(
                "file:records-0001.jsonl", "dir:s1",
                "file:meta.json.tmp", "dir:s1"), events);
    }

    @Test
    void 提交的流水走分段滚动而不是固定写第一段() throws Exception {
        givenSession("s1");
        java.nio.file.Path sessionDir = dir().sessionDir("s1");
        Files.write(sessionDir.resolve("records-0001.jsonl"), new byte[32 * 1024 * 1024]);

        FileCommitScope scope = new FileCommitScope(dir(), new JsonCodec());
        scope.stageAppend(SessionId.of("s1"),
                new Record(RecordId.of("r1"), RecordRole.USER, "你好", null, 1, 2L));
        scope.commit();

        // 规则出处：09-infrastructure §9.6「流水按段滚动」——写入口唯一，滚动由 RecordStore 决定
        assertTrue(Files.exists(sessionDir.resolve("records-0002.jsonl")));
        assertEquals(1, new FileRecordStore(dir(), new JsonCodec()).last(SessionId.of("s1"), 10).size());
    }

    private void givenSession(String id) {
        new FileSessionRepository(dir(), new JsonCodec())
                .save(Session.create(SessionId.of(id), null, AgentId.of("a"), ModelId.of("m"), 1L, null));
    }
}
