package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Session session(String id, SessionStatus status, SessionId parent) {
        return new Session(SessionId.of(id), parent, AgentId.of("builtin.main"), ModelId.of("m1"),
                status, "标题-" + id, 1739520600000L, null, null);
    }

    @Test
    void 写入后重新加载能读回同样的会话() {
        repo().save(session("s1", SessionStatus.RUNNING, null));

        Session loaded = repo().load(SessionId.of("s1"));

        assertEquals("s1", loaded.id().getValue());
        assertEquals("builtin.main", loaded.agentId().getValue());
        assertEquals(SessionStatus.RUNNING, loaded.status());
        assertEquals("标题-s1", loaded.title());
        assertEquals(1739520600000L, loaded.createdDate());
        assertNull(loaded.parentSessionId());
    }

    @Test
    void listResumable_只返回可恢复的会话() {
        repo().save(session("r1", SessionStatus.RESUMABLE, null));
        repo().save(session("r2", SessionStatus.RUNNING, null));

        List<SessionId> ids = repo().listResumable();

        assertEquals(1, ids.size());
        assertEquals("r1", ids.get(0).getValue());
    }

    @Test
    void 子会话落在主会话的_sub_下() {
        repo().save(session("root1", SessionStatus.RUNNING, null));
        repo().save(session("child1", SessionStatus.RUNNING, SessionId.of("root1")));

        assertTrue(tmp.resolve("sessions/root1/meta.json").toFile().exists());
        assertEquals("root1", repo().load(SessionId.of("child1")).parentSessionId().getValue());
    }

    @Test
    void archive_把目录移到_archive() {
        repo().save(session("s9", SessionStatus.RUNNING, null));

        repo().archive(SessionId.of("s9"));

        assertTrue(tmp.resolve("archive/s9/meta.json").toFile().exists());
        assertTrue(!tmp.resolve("sessions/s9").toFile().exists());
    }

    @Test
    void delete_删除整个会话目录() {
        repo().save(session("s8", SessionStatus.RUNNING, null));

        repo().delete(SessionId.of("s8"));

        assertTrue(!tmp.resolve("sessions/s8").toFile().exists());
    }
}
