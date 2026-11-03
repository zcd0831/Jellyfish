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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流水：只追加、按 seq 取最近 n 条与区间。
 * 规则出处：04-perception §4.8.1 规则 5、09-infrastructure §9.4。
 */
class FileRecordStoreTest {

    @TempDir
    Path tmp;

    private DataDirectory dir() {
        return DataDirectory.of(tmp.toString());
    }

    private void givenSession(String id) {
        new FileSessionRepository(dir(), new JsonCodec())
                .save(Session.create(SessionId.of(id), null, AgentId.of("a"), ModelId.of("m"), 1L, null));
    }

    private static Record record(String id, int seq) {
        return new Record(RecordId.of(id), RecordRole.USER, "内容" + seq, null, seq, 1000L + seq);
    }

    @Test
    void 追加后能按最近条数与区间读回() {
        givenSession("s1");
        FileRecordStore store = new FileRecordStore(dir(), new JsonCodec());

        store.appendAll(SessionId.of("s1"), Arrays.asList(record("r1", 1), record("r2", 2), record("r3", 3)));

        List<Record> lastTwo = store.last(SessionId.of("s1"), 2);
        assertEquals(2, lastTwo.size());
        assertEquals("r2", lastTwo.get(0).id().getValue());
        assertEquals("r3", lastTwo.get(1).id().getValue());

        List<Record> range = store.range(SessionId.of("s1"), RecordId.of("r1"), RecordId.of("r2"));
        assertEquals(2, range.size());
        assertEquals(1, range.get(0).sequence());
        assertEquals(2, range.get(1).sequence());
    }

    @Test
    void reasoning与摘要覆盖边界都能落盘读回() {
        givenSession("s1");
        FileRecordStore store = new FileRecordStore(dir(), new JsonCodec());

        store.appendAll(SessionId.of("s1"), Arrays.asList(
                new Record(RecordId.of("r1"), RecordRole.TOOL_REQUEST,
                        "{\"callId\":\"c1\"}", "内部推理", null, 0, 1, 1000L),
                new Record(RecordId.of("r2"), RecordRole.SUMMARY,
                        "摘要正文", null, null, 1, 2, 1001L)));

        List<Record> all = store.last(SessionId.of("s1"), 10);
        assertEquals("内部推理", all.get(0).reasoning());
        assertEquals(1, all.get(1).summaryCoversUpTo());
        assertEquals("摘要正文", all.get(1).content());
    }

    @Test
    void 一次提交的批量追加只落盘一次文件并收尾目录() {
        givenSession("s1");
        FileRecordStore store = new FileRecordStore(dir(), new JsonCodec());
        String sessionDirName = dir().sessionDir("s1").getFileName().toString();

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
            store.appendAll(SessionId.of("s1"), Arrays.asList(record("r1", 1), record("r2", 2), record("r3", 3)));
        } finally {
            AtomicFiles.useSyncForTest(null);
        }

        // 规则出处：09-infrastructure §9.6「② 所有 stageAppend → 追加；全部写完后 fsync 记录文件」
        assertEquals(Arrays.asList("file:records-0001.jsonl", "dir:" + sessionDirName), events);
        assertEquals(3, store.last(SessionId.of("s1"), 10).size());
    }

    @Test
    void 段达到上限后开新段并让新目录项落盘() throws Exception {
        givenSession("s1");
        java.nio.file.Path sessionDir = dir().sessionDir("s1");
        Files.write(sessionDir.resolve("records-0001.jsonl"), new byte[32 * 1024 * 1024]);
        FileRecordStore store = new FileRecordStore(dir(), new JsonCodec());

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
            store.appendAll(SessionId.of("s1"), Collections.singletonList(record("r1", 1)));
        } finally {
            AtomicFiles.useSyncForTest(null);
        }

        // 规则出处：09-infrastructure §9.6「流水按段滚动：单段达到 32 MiB 后开新段，新段创建后立即 fsync 目录」
        assertEquals(Arrays.asList("file:records-0002.jsonl", "dir:" + sessionDir.getFileName()), events);
        assertEquals(1, Files.readAllLines(sessionDir.resolve("records-0002.jsonl"), StandardCharsets.UTF_8).size());
    }

    @Test
    void 末尾半行被跳过而不是让整个会话读不出() throws Exception {
        givenSession("s1");
        FileRecordStore store = new FileRecordStore(dir(), new JsonCodec());
        store.appendAll(SessionId.of("s1"), Arrays.asList(record("r1", 1), record("r2", 2)));
        // 模拟崩在写第三条中间：末尾没有换行
        java.nio.file.Path segment = dir().sessionDir("s1").resolve("records-0001.jsonl");
        Files.write(segment, "{\"id\":\"r3\"".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        // 规则出处：09-infrastructure §9.7「读取时遇到末尾半行一律跳过，不视为错误」
        List<Record> all = store.last(SessionId.of("s1"), 10);

        assertEquals(2, all.size());
        assertEquals("r2", all.get(1).id().getValue());
    }
}
