package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条对话消息；role 复用流水角色，保证"窗口 → 请求"是一次搬家而不是翻译。
 * 规则出处：模块-行动 §5、§8.2 第 2 条。
 */
public final class ChatMessage {

    private final RecordRole role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;

    private ChatMessage(RecordRole role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(
                toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls));
        this.toolCallId = toolCallId;
    }

    public static ChatMessage of(RecordRole role, String content) {
        return new ChatMessage(role, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(RecordRole.ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(RecordRole.TOOL_RESULT, content, null, toolCallId);
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
}
