package io.github.zcd0831.jellyfish.domain.perception.policy;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

/** 运行环境策略单例。规则出处：模块-感知 §7.2、§3.2。 */
public class RuntimePolicy {

    private int maxRetries;
    private int reflectionThreshold;
    private int maxReplanGeneration;
    private int maxReActRounds;
    private int summarizationTokenThreshold;
    private int inputSafetyMargin;
    private int contextWindowRecordCount;
    private double systemRecordRatioLimit;
    private int toolResultTruncateLength;
    private int subagentExecutionTimeoutMillis;
    private int experienceCapacity;
    private int experienceTopN;
    private int experiencePerPlan;
    private double experienceInitialConfidence;
    private double experienceConfidenceStep;
    private double experienceRetireThreshold;
    private double budgetWarnRatio;
    private List<ModelId> modelFallbackChain;
    private List<ToolId> trustedToolIds;
}
