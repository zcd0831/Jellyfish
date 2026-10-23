package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.RecordId;
/** 上下文流水的一条。规则出处：模块-感知 §7.1 规则 5（只追加）。 */
public class Record {

    private RecordId id;
    private RecordRole role;
    private String content;
    private ToolResultPayload toolPayload;
    private int sequence;
    private long date;

    public Record(RecordId id, RecordRole role, String content, ToolResultPayload toolPayload, int sequence, long date) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.toolPayload = toolPayload;
        this.sequence = sequence;
        this.date = date;
    }

    public RecordId id() {
        return id;
    }

    public RecordRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public ToolResultPayload toolPayload() {
        return toolPayload;
    }

    public int sequence() {
        return sequence;
    }

    public long date() {
        return date;
    }
}
