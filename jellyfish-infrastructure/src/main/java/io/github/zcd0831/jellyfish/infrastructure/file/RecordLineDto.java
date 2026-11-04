package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;

/**
 * records-NNNN.jsonl 的一行。
 * 规则出处：09-infrastructure §9.5（一行一个 JSON 对象）。
 */
public class RecordLineDto {

    public RecordId id;
    public RecordRole role;
    public String content;
    /** 模型私有思维状态；只回放给产出的那个模型（04-perception §4.8.1-15）。 */
    public String reasoning;
    /** 一次模型响应的标识；同响应的 ASSISTANT / TOOL_REQUEST / TOOL_RESULT 共享（规则 14/17）。 */
    public String turnId;
    /** 产出该轮响应的模型；reasoning 按它过滤（规则 15）。 */
    public String producerModelId;
    /** 工具结果附属信息；字段名与磁盘上的名字一致（历史文件写的就是 `truncated`）。 */
    public ToolPayloadDto toolPayload;
    /** 只占 SUMMARY：该摘要覆盖到（含）哪条记录的 sequence（04-perception §4.8.1-9/11）。 */
    public int summaryCoversUpTo;
    public int sequence;
    public long date;

    /** 04-perception §4.6 的 ToolResultPayload 的本层形状：与域 VO 分离，避免 getter 名污染磁盘格式。 */
    public static class ToolPayloadDto {

        public boolean truncated;
        public String resourceUri;
    }
}
