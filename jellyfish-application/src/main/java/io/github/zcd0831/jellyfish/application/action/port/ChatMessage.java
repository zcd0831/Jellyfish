package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条对话消息；role 复用流水角色，保证"窗口 → 请求"是一次搬家而不是翻译。
 * 规则出处：05-action §5.6、§5.9.2 第 2 条、§5.9.2 第 6 条（reasoning 原样回传）。
 */
public final class ChatMessage {

    private final RecordRole role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String reasoning;

    private ChatMessage(RecordRole role, String content, List<ToolCall> toolCalls, String toolCallId,
                        String reasoning) {
        this.role = role;
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(
                toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls));
        this.toolCallId = toolCallId;
        this.reasoning = reasoning;
    }

    public static ChatMessage of(RecordRole role, String content) {
        return new ChatMessage(role, content, null, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(RecordRole.ASSISTANT, content, toolCalls, null, null);
    }

    /** 带思维内容的 assistant 消息：只有它带 `tool_calls` 时协议侧才会回传 `reasoning_content`。 */
    public static ChatMessage assistant(String content, List<ToolCall> toolCalls, String reasoning) {
        return new ChatMessage(RecordRole.ASSISTANT, content, toolCalls, null, reasoning);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(RecordRole.TOOL_RESULT, content, null, toolCallId, null);
    }

    public RecordRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String reasoning() {
        return reasoning;
    }
}
