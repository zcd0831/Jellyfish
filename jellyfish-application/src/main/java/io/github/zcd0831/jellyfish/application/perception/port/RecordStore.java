package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import java.util.List;

/**
 * 流水追加与读取；区间基于 seq。规则出处：模块-基础设施层 §3。
 */
public interface RecordStore {

    void append(SessionId sessionId, Record record);

    List<Record> last(SessionId sessionId, int count);

    List<Record> range(SessionId sessionId, RecordId start, RecordId end);
}
