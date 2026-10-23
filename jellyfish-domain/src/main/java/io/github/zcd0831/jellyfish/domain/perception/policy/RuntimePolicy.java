package io.github.zcd0831.jellyfish.domain.perception.policy;

import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 运行环境策略单例，只有用户能改。规则出处：模块-感知 §3.2、§7.2。 */
public class RuntimePolicy {

    private final int maxRetries;
    private final int reflectionThreshold;
    private final int maxReplanGeneration;
    private final int maxReActRounds;
    private final int summarizationTokenThreshold;
    private final int inputSafetyMargin;
    private final int contextWindowRecordCount;
    private final double systemRecordRatioLimit;
    private final int toolResultTruncateLength;
    private final int subagentExecutionTimeoutMillis;
    private final int experienceCapacity;
    private final int experienceTopN;
    private final int experiencePerPlan;
    private final double experienceInitialConfidence;
    private final double experienceConfidenceStep;
    private final double experienceRetireThreshold;
    private final double budgetWarnRatio;
    private final List<ModelId> modelFallbackChain;
    private final List<ToolId> trustedToolIds;

    public RuntimePolicy(int maxRetries, int reflectionThreshold, int maxReplanGeneration, int maxReActRounds,
                         int summarizationTokenThreshold, int inputSafetyMargin, int contextWindowRecordCount,
                         double systemRecordRatioLimit, int toolResultTruncateLength,
                         int subagentExecutionTimeoutMillis, int experienceCapacity, int experienceTopN,
                         int experiencePerPlan, double experienceInitialConfidence,
                         double experienceConfidenceStep, double experienceRetireThreshold,
                         double budgetWarnRatio, List<ModelId> modelFallbackChain, List<ToolId> trustedToolIds) {
        this.maxRetries = maxRetries;
        this.reflectionThreshold = reflectionThreshold;
        this.maxReplanGeneration = maxReplanGeneration;
        this.maxReActRounds = maxReActRounds;
        this.summarizationTokenThreshold = summarizationTokenThreshold;
        this.inputSafetyMargin = inputSafetyMargin;
        this.contextWindowRecordCount = contextWindowRecordCount;
        this.systemRecordRatioLimit = systemRecordRatioLimit;
        this.toolResultTruncateLength = toolResultTruncateLength;
        this.subagentExecutionTimeoutMillis = subagentExecutionTimeoutMillis;
        this.experienceCapacity = experienceCapacity;
        this.experienceTopN = experienceTopN;
        this.experiencePerPlan = experiencePerPlan;
        this.experienceInitialConfidence = experienceInitialConfidence;
        this.experienceConfidenceStep = experienceConfidenceStep;
        this.experienceRetireThreshold = experienceRetireThreshold;
        this.budgetWarnRatio = budgetWarnRatio;
        this.modelFallbackChain = Collections.unmodifiableList(new ArrayList<ModelId>(
                modelFallbackChain == null ? Collections.<ModelId>emptyList() : modelFallbackChain));
        this.trustedToolIds = Collections.unmodifiableList(new ArrayList<ToolId>(
                trustedToolIds == null ? Collections.<ToolId>emptyList() : trustedToolIds));
    }

    /** 平台初始化写入的默认值；规则出处：模块-感知 §7.2、D08/D10 的常量口径。 */
    public static RuntimePolicy defaults() {
        return new RuntimePolicy(3, 2, 2, 20, 100000, 10000, 20, 0.6, 4000, 60000,
                200, 5, 3, 0.5, 0.1, 0.2, 0.8,
                Collections.<ModelId>emptyList(), Collections.<ToolId>emptyList());
    }

    /** 规则出处：模块-感知 §7.2 规则 5（信任清单 = 免审批）。 */
    public boolean isToolTrusted(ToolId toolId) {
        return trustedToolIds.contains(toolId);
    }

    /** 规则出处：模块-感知 §7.2 规则 3（阈值独立配置，超过 maxRetries 即永不反思）。 */
    public boolean needsReflection(int failedAttempts) {
        return reflectionThreshold <= maxRetries && failedAttempts >= reflectionThreshold;
    }

    public List<ModelId> modelFallbackChain() {
        return modelFallbackChain;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public int reflectionThreshold() {
        return reflectionThreshold;
    }

    public int maxReplanGeneration() {
        return maxReplanGeneration;
    }

    public int maxReActRounds() {
        return maxReActRounds;
    }

    public int summarizationTokenThreshold() {
        return summarizationTokenThreshold;
    }

    public int inputSafetyMargin() {
        return inputSafetyMargin;
    }

    public int contextWindowRecordCount() {
        return contextWindowRecordCount;
    }

    public double systemRecordRatioLimit() {
        return systemRecordRatioLimit;
    }

    public int toolResultTruncateLength() {
        return toolResultTruncateLength;
    }

    public int subagentExecutionTimeoutMillis() {
        return subagentExecutionTimeoutMillis;
    }

    public int experienceCapacity() {
        return experienceCapacity;
    }

    public int experienceTopN() {
        return experienceTopN;
    }

    public int experiencePerPlan() {
        return experiencePerPlan;
    }

    public double experienceInitialConfidence() {
        return experienceInitialConfidence;
    }

    public double experienceConfidenceStep() {
        return experienceConfidenceStep;
    }

    public double experienceRetireThreshold() {
        return experienceRetireThreshold;
    }

    public double budgetWarnRatio() {
        return budgetWarnRatio;
    }

    public List<ToolId> trustedToolIds() {
        return trustedToolIds;
    }
}
