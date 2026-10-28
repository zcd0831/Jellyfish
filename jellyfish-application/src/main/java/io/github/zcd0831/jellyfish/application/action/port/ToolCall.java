package io.github.zcd0831.jellyfish.application.action.port;

/**
 * 模型请求的一次工具调用：callId 用于把结果回灌到同一调用上。
 * 规则出处：05-action §5.6。
 */
public final class ToolCall {

    private final String callId;
    private final String name;
    private final String argumentsJson;

    public ToolCall(String callId, String name, String argumentsJson) {
        this.callId = callId;
        this.name = name;
        this.argumentsJson = argumentsJson;
    }

    public String callId() {
        return callId;
    }

    public String name() {
        return name;
    }

    public String argumentsJson() {
        return argumentsJson;
    }
}
