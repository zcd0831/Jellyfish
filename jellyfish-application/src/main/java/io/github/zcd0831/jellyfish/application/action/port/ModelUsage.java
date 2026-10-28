package io.github.zcd0831.jellyfish.application.action.port;

/**
 * 一次调用的用量：厂商给了就是精确值，没给时回退本地估算并由 {@link #estimated()} 标出。
 * 规则出处：05-action §5.9.4（用量展示口径）。
 */
public final class ModelUsage {

    private final long inputTokens;
    private final long outputTokens;
    private final boolean estimated;

    public ModelUsage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, false);
    }

    public ModelUsage(long inputTokens, long outputTokens, boolean estimated) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.estimated = estimated;
    }

    /** 厂商未返回 usage、由本地计数兜底。界面据此加 ≈，不要把估算当精确值。 */
    public boolean estimated() {
        return estimated;
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
