package io.github.zcd0831.jellyfish.application.perception.port;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import java.util.List;

/**
 * 流水追加与读取；区间基于 seq。规则出处：09-infrastructure §9.4、§9.6。
 */
public interface RecordStore {

    /**
     * 一次提交的流水批量追加：全部写完后只 fsync 一次。
     * 规则出处：09-infrastructure §9.6「② 所有 stageAppend → 追加；全部写完后 fsync 记录文件」。
     */
    void appendAll(SessionId sessionId, List<Record> records);

    List<Record> last(SessionId sessionId, int count);

    List<Record> range(SessionId sessionId, RecordId start, RecordId end);
}
