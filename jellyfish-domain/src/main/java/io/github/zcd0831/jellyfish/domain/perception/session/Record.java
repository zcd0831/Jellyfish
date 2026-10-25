package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.RecordId;

/** 上下文流水的一条。规则出处：模块-感知 §7.1 规则 5（只追加）、规则 14/15/17。 */
public class Record {

    private RecordId id;
    private RecordRole role;
    private String content;
    /** 模型私有思维状态（DeepSeek reasoning_content / 签名 thinking / thoughtSignature）；只回放给产者。 */
    private String reasoning;
    /** 一次模型响应的标识：ASSISTANT + 各 TOOL_REQUEST / TOOL_RESULT 共享；USER / SYSTEM / SUMMARY 为空。 */
    private String turnId;
    /** 产出该轮响应的模型；reasoning 只有在目标模型与它相同时才回放。 */
    private String producerModelId;
    private ToolResultPayload toolPayload;
    /** 该摘要覆盖到（含）哪条记录的 sequence；只占 SUMMARY，0 表示不是摘要（规则 9/11）。 */
    private int summaryCoversUpTo;
    private int sequence;
    private long date;

    /** 兼容旧构造（无 reasoning、无 turn、非摘要）：等价于 reasoning/turnId/producerModelId = null。 */
    public Record(RecordId id, RecordRole role, String content, ToolResultPayload toolPayload, int sequence,
                  long date) {
        this(id, role, content, null, null, null, toolPayload, 0, sequence, date);
    }

    /** 兼容上一版构造（有 reasoning、未引入 turn）。 */
    public Record(RecordId id, RecordRole role, String content, String reasoning,
                  ToolResultPayload toolPayload, int summaryCoversUpTo, int sequence, long date) {
        this(id, role, content, reasoning, null, null, toolPayload, summaryCoversUpTo, sequence, date);
    }

    public Record(RecordId id, RecordRole role, String content, String reasoning, String turnId,
                  String producerModelId, ToolResultPayload toolPayload, int summaryCoversUpTo,
                  int sequence, long date) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.turnId = turnId;
        this.producerModelId = producerModelId;
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

    public String turnId() {
        return turnId;
    }

    public String producerModelId() {
        return producerModelId;
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
