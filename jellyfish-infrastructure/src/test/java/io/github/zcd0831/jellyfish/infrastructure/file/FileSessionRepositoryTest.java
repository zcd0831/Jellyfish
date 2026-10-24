package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0 验收：建会话 → 写 meta.json → 重启（新实例）能读回。
 * 规则出处：技术决策 D15（S0 验收标准）。
 */
class FileSessionRepositoryTest {

    @TempDir
    Path tmp;

    private FileSessionRepository repo() {
        return new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
    }

    private static Session session(String id, SessionId parent) {
        return Session.rehydrate(SessionId.of(id), parent, AgentId.of("builtin.main"), ModelId.of("m1"),
                "标题-" + id, 1739520600000L, java.util.Collections.<Record>emptyList(), null);
    }

    @Test
    void 写入后重新加载能读回同样的会话() {
        repo().save(session("s1", null));

        Session loaded = repo().load(SessionId.of("s1"));

        assertEquals("s1", loaded.id().getValue());
        assertEquals("builtin.main", loaded.agentId().getValue());
        assertEquals("标题-s1", loaded.title());
        assertEquals(1739520600000L, loaded.createdDate());
        assertNull(loaded.parentSessionId());
    }

    @Test
    void list_列出所有存在的会话() {
        repo().save(session("r1", null));
        repo().save(session("r2", null));

        List<io.github.zcd0831.jellyfish.application.perception.port.SessionSummary> summaries = repo().list();

        assertEquals(2, summaries.size());
    }

    @Test
    void 子会话落在主会话的_sub_下() {
        repo().save(session("root1", null));
        repo().save(session("child1", SessionId.of("root1")));

        assertTrue(tmp.resolve("sessions/root1/meta.json").toFile().exists());
        assertEquals("root1", repo().load(SessionId.of("child1")).parentSessionId().getValue());
    }

    @Test
    void archive_压成包并删除原目录() {
        repo().save(session("s9", null));

        repo().archive(SessionId.of("s9"));

        assertTrue(tmp.resolve("archive/s9.zip").toFile().exists(), "归档 = 压缩成包（D22）");
        assertTrue(!tmp.resolve("sessions/s9").toFile().exists());
        assertTrue(!tmp.resolve("archive/s9.zip.tmp").toFile().exists(), "不应留下半截包");
    }

    @Test
    void delete_删除整个会话目录() {
        repo().save(session("s8", null));

        repo().delete(SessionId.of("s8"));

        assertTrue(!tmp.resolve("sessions/s8").toFile().exists());
    }

    @Test
    void 归档包里含元数据与子会话() throws Exception {
        repo().save(session("root2", null));
        repo().save(session("child2", SessionId.of("root2")));

        repo().archive(SessionId.of("root2"));

        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(tmp.resolve("archive/root2.zip").toFile());
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
            assertTrue(names.contains("meta.json"), "包内应有会话头：" + names);
            assertTrue(names.contains("sub/child2/meta.json"), "子会话也要进包：" + names);
        } finally {
            zip.close();
        }
    }

    @Test
    void 归档中间态对账丢弃半截包并补删原目录() throws Exception {
        repo().save(session("s7", null));
        java.nio.file.Files.createDirectories(tmp.resolve("archive"));
        java.nio.file.Files.write(tmp.resolve("archive/s7.zip.tmp"), new byte[]{1, 2, 3});

        repo().cleanupArchiveResidue();

        assertFalse(java.nio.file.Files.exists(tmp.resolve("archive/s7.zip.tmp")), "半截包应被丢弃");
        assertTrue(java.nio.file.Files.exists(tmp.resolve("sessions/s7/meta.json")), "未完成的归档不动原目录");

        java.nio.file.Files.write(tmp.resolve("archive/s7.zip"), new byte[]{1});
        repo().cleanupArchiveResidue();

        assertFalse(java.nio.file.Files.exists(tmp.resolve("sessions/s7")), "包已完整则补删原目录");
    }
}
