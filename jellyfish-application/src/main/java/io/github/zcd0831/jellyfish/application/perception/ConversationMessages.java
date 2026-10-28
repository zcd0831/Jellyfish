package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.shared.Json;
import io.github.zcd0831.jellyfish.domain.perception.session.ContextWindow;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 窗口 → 请求消息。摘要作为 user 回灌；SYSTEM 注入按 sequence 归位（不进 system 字段）；
 * 同一 turnId 还原成一条 assistant 消息 + 按调用顺序排列的 tool 消息；reasoning 只回放给产者模型。
 * 规则出处：04-perception §4.8.1 规则 9/14/17，§4.9.5。
 */
public final class ConversationMessages {

    /** 摘要作为一条 user 消息回灌；前缀是模型可见文案，不是用户文案。 */
    private static final String SUMMARY_PREFIX = "【更早对话的摘要】\n";

    private ConversationMessages() {
    }

    public static List<ChatMessage> of(ContextWindow window, ModelId targetModelId) {
        return of(window.getRecords(), targetModelId);
    }

    public static List<ChatMessage> of(List<Record> records, ModelId targetModelId) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        int i = 0;
        while (i < records.size()) {
            Record record = records.get(i);
            if (record.role() == RecordRole.SUMMARY) {
                messages.add(ChatMessage.of(RecordRole.SUMMARY, SUMMARY_PREFIX + record.content()));
                i++;
                continue;
            }
            if (record.turnId() == null) {
                messages.add(ChatMessage.of(record.role(), record.content()));
                i++;
                continue;
            }
            int end = i;
            while (end < records.size() && record.turnId().equals(records.get(end).turnId())) {
                end++;
            }
            appendTurn(records.subList(i, end), targetModelId, messages);
            i = end;
        }
        return messages;
    }

    /** 一轮模型输出：一条 assistant 消息 + 若干 tool 消息。 */
    private static void appendTurn(List<Record> turn, ModelId targetModelId, List<ChatMessage> messages) {
        String content = null;
        String reasoning = null;
        String producer = null;
        List<ToolCall> calls = new ArrayList<ToolCall>();
        List<String> results = new ArrayList<String>();
        for (Record record : turn) {
            if (record.role() == RecordRole.ASSISTANT) {
                content = record.content();
            } else if (record.role() == RecordRole.TOOL_REQUEST) {
                calls.addAll(parseCalls(record.content()));
            } else if (record.role() == RecordRole.TOOL_RESULT) {
                results.add(record.content());
            }
            if (reasoning == null) {
                reasoning = record.reasoning();
            }
            if (producer == null) {
                producer = record.producerModelId();
            }
        }
        String replayReasoning = producer != null && producer.equals(targetModelId.getValue()) ? reasoning : null;
        if (calls.isEmpty()) {
            if (content != null) {
                messages.add(ChatMessage.assistant(content, Collections.<ToolCall>emptyList(), replayReasoning));
            }
            return;
        }
        if (calls.size() != results.size()) {
            throw new DomainInvariantViolation("工具请求与结果数量不一致：轮 " + turn.get(0).turnId());
        }
        messages.add(ChatMessage.assistant(content, calls, replayReasoning));
        for (int k = 0; k < calls.size(); k++) {
            messages.add(ChatMessage.toolResult(calls.get(k).callId(), results.get(k)));
        }
    }

    private static List<ToolCall> parseCalls(String content) {
        Map<String, Object> fields = Json.parseObject(content);
        Object name = fields.get("name");
        if (name == null) {
            return Collections.emptyList();
        }
        Object callId = fields.get("callId");
        Object arguments = fields.get("arguments");
        return Collections.singletonList(new ToolCall(
                callId == null ? "" : String.valueOf(callId), String.valueOf(name),
                arguments == null ? "{}" : String.valueOf(arguments)));
    }
}
