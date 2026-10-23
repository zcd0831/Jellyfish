package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流水：只追加、按 seq 取最近 n 条与区间。
 * 规则出处：模块-感知 §7.1 规则 5、模块-基础设施层 §3。
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

        store.append(SessionId.of("s1"), record("r1", 1));
        store.append(SessionId.of("s1"), record("r2", 2));
        store.append(SessionId.of("s1"), record("r3", 3));

        List<Record> lastTwo = store.last(SessionId.of("s1"), 2);
        assertEquals(2, lastTwo.size());
        assertEquals("r2", lastTwo.get(0).id().getValue());
        assertEquals("r3", lastTwo.get(1).id().getValue());

        List<Record> range = store.range(SessionId.of("s1"), RecordId.of("r1"), RecordId.of("r2"));
        assertEquals(2, range.size());
        assertEquals(1, range.get(0).sequence());
        assertEquals(2, range.get(1).sequence());
    }
}
