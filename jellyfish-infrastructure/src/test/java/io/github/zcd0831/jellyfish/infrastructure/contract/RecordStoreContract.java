package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `RecordStore` 的契约：只追加、按序号读取。 */
public abstract class RecordStoreContract {

    protected abstract RecordStore store();

    protected abstract SessionRepository sessions();

    @BeforeEach
    void givenSession() {
        sessions().save(Session.create(SessionId.of("s1"), null, AgentId.of("a"), ModelId.of("m1"), 1L, null));
    }

    private static Record record(String id, int seq) {
        return new Record(RecordId.of(id), RecordRole.USER, "内容" + seq, null, seq, 1000L + seq);
    }

    @Test
    void 追加后按最近n条读取() {
        store().append(SessionId.of("s1"), record("r1", 1));
        store().append(SessionId.of("s1"), record("r2", 2));

        List<Record> last = store().last(SessionId.of("s1"), 1);

        assertEquals(1, last.size());
        assertEquals("r2", last.get(0).id().getValue());
    }

    @Test
    void 区间读取包含两端() {
        store().append(SessionId.of("s1"), record("r1", 1));
        store().append(SessionId.of("s1"), record("r2", 2));
        store().append(SessionId.of("s1"), record("r3", 3));

        List<Record> range = store().range(SessionId.of("s1"), RecordId.of("r1"), RecordId.of("r3"));

        assertEquals(3, range.size());
        assertEquals(1, range.get(0).sequence());
        assertEquals(3, range.get(2).sequence());
    }

    @Test
    void 空会话返回空列表() {
        assertTrue(store().last(SessionId.of("s1"), 5).isEmpty());
    }

    @Test
    void 区间端点不存在时抛异常() {
        store().append(SessionId.of("s1"), record("r1", 1));

        assertThrows(IllegalArgumentException.class,
                () -> store().range(SessionId.of("s1"), RecordId.of("r1"), RecordId.of("missing")));
    }
}
