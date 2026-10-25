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
    /** 只占 TOOL_REQUEST：产出该工具调用请求的 assistant 消息的 reasoning_content（模块-感知 §7.1-15）。 */
    public String reasoning;
    public ToolResultPayload toolPayload;
    /** 只占 SUMMARY：该摘要覆盖到（含）哪条记录的 sequence（模块-感知 §7.1-9/11）。 */
    public int summaryCoversUpTo;
    public int sequence;
    public long date;
}
