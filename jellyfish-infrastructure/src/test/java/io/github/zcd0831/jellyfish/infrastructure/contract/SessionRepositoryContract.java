package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `SessionRepository` 的契约：任何实现都要满足。
 * 规则出处：模块-应用层 §6、技术决策 D13（全部 9 个仓储做契约测试）。
 */
public abstract class SessionRepositoryContract {

    protected abstract SessionRepository repository();

    private static Session session(String id, SessionStatus status, SessionId parent) {
        return Session.rehydrate(SessionId.of(id), parent, AgentId.of("builtin.main"), ModelId.of("m1"),
                status, "标题-" + id, 1000L, java.util.Collections.emptyList(), null);
    }

    @Test
    void 保存后能按id读回同样的会话头() {
        repository().save(session("s1", SessionStatus.RUNNING, null));

        Session loaded = repository().load(SessionId.of("s1"));

        assertEquals("s1", loaded.id().getValue());
        assertEquals(SessionStatus.RUNNING, loaded.status());
        assertEquals("标题-s1", loaded.title());
        assertEquals(1000L, loaded.createdDate());
    }

    @Test
    void 不存在的会话返回null而不是抛异常() {
        assertNull(repository().load(SessionId.of("nope")));
    }

    @Test
    void listResumable只返回可续跑的会话() {
        repository().save(session("r1", SessionStatus.RESUMABLE, null));
        repository().save(session("r2", SessionStatus.RUNNING, null));

        List<SessionId> ids = repository().listResumable();

        assertEquals(1, ids.size());
        assertEquals("r1", ids.get(0).getValue());
    }

    @Test
    void 子会话的父id被保留() {
        repository().save(session("root", SessionStatus.RUNNING, null));
        repository().save(session("child", SessionStatus.RUNNING, SessionId.of("root")));

        assertEquals("root", repository().load(SessionId.of("child")).parentSessionId().getValue());
    }

    @Test
    void archive把会话移出可读范围() {
        repository().save(session("s9", SessionStatus.RUNNING, null));

        repository().archive(SessionId.of("s9"));

        assertNull(repository().load(SessionId.of("s9")));
    }

    @Test
    void delete后会话不可读() {
        repository().save(session("s8", SessionStatus.RUNNING, null));

        repository().delete(SessionId.of("s8"));

        assertNull(repository().load(SessionId.of("s8")));
        assertTrue(repository().listResumable().isEmpty());
    }
}
