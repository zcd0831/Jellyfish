package io.github.zcd0831.jellyfish.application.action.port;

/**
 * 模型调用结果：文本 + 用量；记账由调用方完成。
 * 规则出处：技术决策 D08（适配器不记账）。
 */
public final class ModelInvocationResult {

    private final String text;
    private final long usageTokens;

    public ModelInvocationResult(String text, long usageTokens) {
        this.text = text;
        this.usageTokens = usageTokens;
    }

    public String text() {
        return text;
    }

    public long usageTokens() {
        return usageTokens;
    }
}
