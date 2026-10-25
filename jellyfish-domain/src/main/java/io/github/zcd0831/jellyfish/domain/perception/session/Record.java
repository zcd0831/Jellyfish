package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.RecordId;

/** 上下文流水的一条。规则出处：模块-感知 §7.1 规则 5（只追加）、规则 14/15。 */
public class Record {

    private RecordId id;
    private RecordRole role;
    private String content;
    /** 产出该轮工具调用请求的 assistant 消息的 reasoning_content；只占 TOOL_REQUEST（规则 15）。 */
    private String reasoning;
    private ToolResultPayload toolPayload;
    /** 该摘要覆盖到（含）哪条记录的 sequence；只占 SUMMARY，0 表示不是摘要（规则 9/11）。 */
    private int summaryCoversUpTo;
    private int sequence;
    private long date;

    /** 兼容旧构造（无 reasoning、非摘要）：等价于 reasoning = null、summaryCoversUpTo = 0。 */
    public Record(RecordId id, RecordRole role, String content, ToolResultPayload toolPayload, int sequence,
                  long date) {
        this(id, role, content, null, toolPayload, 0, sequence, date);
    }

    public Record(RecordId id, RecordRole role, String content, String reasoning,
                  ToolResultPayload toolPayload, int summaryCoversUpTo, int sequence, long date) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.toolPayload = toolPayload;
        this.summaryCoversUpTo = summaryCoversUpTo;
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

    public String reasoning() {
        return reasoning;
    }

    public ToolResultPayload toolPayload() {
        return toolPayload;
    }

    public int summaryCoversUpTo() {
        return summaryCoversUpTo;
    }

    public int sequence() {
        return sequence;
    }

    public long date() {
        return date;
    }
}
