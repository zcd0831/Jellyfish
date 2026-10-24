package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话状态迁移与窗口推导的边界分支。规则出处：模块-感知 §7.1。 */
class SessionBranchesTest {

    private static Session session() {
        return Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
    }

    @Test
    void 已归档会话不能取消也不能续跑() {
        Session session = session();
        session.archive();

        assertThrows(DomainInvariantViolation.class, session::cancel);
        assertThrows(DomainInvariantViolation.class, session::resume);
    }

    @Test
    void 标记可续跑只对运行中生效() {
        Session session = session();
        session.archive();
        session.markResumable();
        assertEquals(SessionStatus.ARCHIVED, session.status(), "归档不会被降级为可续跑");

        Session running = session();
        running.markResumable();
        assertEquals(SessionStatus.RESUMABLE, running.status());
    }

    @Test
    void 空流水的窗口为空() {
        ContextWindow window = session().buildContextForLlm(new ContextWindowPolicy(5, 0.9),
                text -> text == null ? 0 : text.length(), 1000);

        assertTrue(window.getRecords().isEmpty());
        assertEquals(0, window.getUsage().getUsedTokens());
    }

    @Test
    void 窗口条数为零时只剩SYSTEM与摘要() {
        Session session = session();
        session.appendRecord(RecordRole.SYSTEM, "注入", null, 1L);
        session.appendRecord(RecordRole.USER, "u1", null, 2L);
        session.markSummarized("摘要", 3L);

        ContextWindow window = session.buildContextForLlm(new ContextWindowPolicy(0, 0.99),
                text -> 1, 100);

        assertEquals(2, window.getRecords().size());
        assertEquals(RecordRole.SYSTEM, window.getRecords().get(0).role());
        assertEquals(RecordRole.SUMMARY, window.getRecords().get(1).role());
    }

    @Test
    void 流水装回会替换旧内容() {
        Session session = session();
        Record record = session.appendRecord(RecordRole.USER, "旧", null, 1L);

        session.hydrateRecords(Collections.singletonList(record));

        assertEquals(1, session.records().size());
        assertEquals("旧", session.records().get(0).content());
    }

    @Test
    void 记录序号从空流水开始为一() {
        assertEquals(1, session().appendRecord(RecordRole.USER, "u", null, 1L).sequence());
    }
}
