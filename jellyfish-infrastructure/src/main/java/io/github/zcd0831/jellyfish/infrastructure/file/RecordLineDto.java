package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;

/**
 * records-NNNN.jsonl 的一行。
 * 规则出处：模块-基础设施层 §4（一行一个 JSON 对象）。
 */
public class RecordLineDto {

    public RecordId id;
    public RecordRole role;
    public String content;
    public ToolResultPayload toolPayload;
    public int sequence;
    public long date;
}
