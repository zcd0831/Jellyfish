package io.github.zcd0831.jellyfish.application.action.port;

/**
 * 一次调用的精确用量：台账口径，只有厂商不给时才回退估算。
 * 规则出处：技术决策 D08 第 1 节。
 */
public final class ModelUsage {

    private final long inputTokens;
    private final long outputTokens;

    public ModelUsage(long inputTokens, long outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long total() {
        return inputTokens + outputTokens;
    }
}
