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

/** 会话取消与窗口推导的边界分支。规则出处：04-perception §4.8.1。 */
class SessionBranchesTest {

    private static Session session() {
        Session created = Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null);
        created.pullEvents();  // 创建时已 raise SessionStarted（04-perception §4.7）
        return created;
    }

    @Test
    void 取消只发事件不改任何持久事实() {
        Session session = session();

        session.cancel();

        assertEquals(1, session.pullEvents().size());
        assertEquals(0, session.pullEvents().size(), "事件取走后不再重复");
    }

    @Test
    void 空流水的窗口为空() {
        ContextWindow window = session().buildContextForLlm(text -> text == null ? 0 : text.length(), 1000);

        assertTrue(window.getRecords().isEmpty());
        assertEquals(0, window.getUsage().getUsedTokens());
    }

    @Test
    void 摘要覆盖全部记录时窗口只剩摘要本身() {
        Session session = session();
        session.appendRecord(RecordRole.SYSTEM, "注入", null, 1L);
        session.appendRecord(RecordRole.USER, "u1", null, 2L);
        session.markSummarized("摘要", 2, 3L);

        ContextWindow window = session.buildContextForLlm(text -> 1, 100);

        assertEquals(1, window.getRecords().size());
        assertEquals(RecordRole.SUMMARY, window.getRecords().get(0).role());
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
