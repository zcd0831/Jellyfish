package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型调用结果：文本 + 工具调用 + 用量 + 实际服务的模型。
 * 规则出处：模块-行动 §5、§8.2 第 3 条；适配器不记账（D08）。
 */
public final class ModelInvocationResult {

    private final String text;
    private final List<ToolCall> toolCalls;
    private final ModelUsage usage;
    private final ModelId servedModelId;

    public ModelInvocationResult(String text, List<ToolCall> toolCalls, ModelUsage usage, ModelId servedModelId) {
        this.text = text;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(
                toolCalls == null ? Collections.<ToolCall>emptyList() : toolCalls));
        this.usage = usage;
        this.servedModelId = servedModelId;
    }

    public String text() {
        return text;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public ModelUsage usage() {
        return usage;
    }

    /** 降级后与请求的目标不同，调用方据此执行 Session.switchModel。 */
    public ModelId servedModelId() {
        return servedModelId;
    }
}
