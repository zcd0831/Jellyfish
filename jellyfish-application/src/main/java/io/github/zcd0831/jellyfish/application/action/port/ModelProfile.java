package io.github.zcd0831.jellyfish.application.action.port;

/**
 * 模型档案：感知据此算有效上限，不直接引用 Model 聚合。
 * 规则出处：模块-感知 §4.6（ModelProfile）。
 */
public final class ModelProfile {

    private final int maxInputTokens;
    private final int maxOutputTokens;

    public ModelProfile(int maxInputTokens, int maxOutputTokens) {
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    public int maxInputTokens() {
        return maxInputTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }
}
