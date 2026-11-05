package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.RecordId;

/** 上下文流水的一条。规则出处：04-perception §4.8.1 规则 5（只追加）、规则 14/15/17。 */
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

    /**
     * 一条流水记录只有两条诞生路径，都由这里收口：
     * <ul>
     *   <li>{@link #restore}：持久化形态重建（`id` / `sequence` / `date` 由盘上读回）；</li>
     *   <li>{@link #of}：最简单的"角色 + 正文"，编号仍由调用方给出。</li>
     * </ul>
     * 追加时的编号（`sequence`）与时间由 {@link Session} 决定，不允许外部指定顺序。
     */
    private Record(RecordId id, RecordDraft draft, int sequence, long date) {
        this.id = id;
        this.role = draft.role();
        this.content = draft.content();
        this.reasoning = draft.reasoning();
        this.turnId = draft.turnId();
        this.producerModelId = draft.producerModelId();
        this.toolPayload = draft.toolPayload();
        this.summaryCoversUpTo = draft.summaryCoversUpTo();
        this.sequence = sequence;
        this.date = date;
    }

    /** 从持久化形态重建一条记录；`id` / `sequence` / `date` 原样带回。 */
    public static Record restore(RecordId id, RecordDraft draft, int sequence, long date) {
        return new Record(id, draft, sequence, date);
    }

    /** 只有角色与正文的最常见形态（USER / SYSTEM / ASSISTANT 无私有状态时）。 */
    public static Record of(RecordId id, RecordRole role, String content, int sequence, long date) {
        return new Record(id, RecordDraft.builder(role, content).build(), sequence, date);
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
