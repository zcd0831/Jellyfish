package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
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
 * 规则出处：08-application §8.7。
 */
public abstract class SessionRepositoryContract {

    protected abstract SessionRepository repository();

    private static Session session(String id, SessionId parent) {
        return Session.rehydrate(SessionId.of(id), parent, AgentId.of("builtin.main"), ModelId.of("m1"),
                "标题-" + id, 1000L, java.util.Collections.emptyList(), null);
    }

    @Test
    void 保存后能按id读回同样的会话头() {
        repository().save(session("s1", null));

        Session loaded = repository().load(SessionId.of("s1"));

        assertEquals("s1", loaded.id().getValue());
        assertEquals("标题-s1", loaded.title());
        assertEquals(1000L, loaded.createdDate());
    }

    @Test
    void 不存在的会话返回null而不是抛异常() {
        assertNull(repository().load(SessionId.of("nope")));
    }

    @Test
    void list按创建时间倒序列出所有存在的会话() {
        repository().save(session("r1", null));
        repository().save(session("r2", null));

        List<SessionSummary> summaries = repository().list();

        assertEquals(2, summaries.size());
    }

    @Test
    void 子会话的父id被保留() {
        repository().save(session("root", null));
        repository().save(session("child", SessionId.of("root")));

        assertEquals("root", repository().load(SessionId.of("child")).parentSessionId().getValue());
    }

    @Test
    void archive压缩后会话不可读且不再被列出() {
        repository().save(session("s9", null));

        repository().archive(SessionId.of("s9"));

        assertNull(repository().load(SessionId.of("s9")));
        assertTrue(repository().list().isEmpty());
    }

    @Test
    void delete后会话不可读() {
        repository().save(session("s8", null));

        repository().delete(SessionId.of("s8"));

        assertNull(repository().load(SessionId.of("s8")));
        assertTrue(repository().list().isEmpty());
    }
}
