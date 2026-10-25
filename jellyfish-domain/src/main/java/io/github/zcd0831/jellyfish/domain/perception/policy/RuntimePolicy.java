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
    private final int skillCapacity;
    private final double budgetWarnRatio;
    private final long budgetLimitTokens;
    private final List<ModelId> modelFallbackChain;
    private final List<ToolId> trustedToolIds;

    public RuntimePolicy(int maxRetries, int reflectionThreshold, int maxReplanGeneration, int maxReActRounds,
                         int summarizationTokenThreshold, int inputSafetyMargin, int contextWindowRecordCount,
                         double systemRecordRatioLimit, int toolResultTruncateLength,
                         int subagentExecutionTimeoutMillis, int experienceCapacity, int experienceTopN,
                         int experiencePerPlan, double experienceInitialConfidence,
                         double experienceConfidenceStep, double experienceRetireThreshold,
                         int skillCapacity,
                         double budgetWarnRatio, long budgetLimitTokens,
                         List<ModelId> modelFallbackChain, List<ToolId> trustedToolIds) {
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
        this.skillCapacity = skillCapacity;
        this.budgetWarnRatio = budgetWarnRatio;
        this.budgetLimitTokens = budgetLimitTokens;
        this.modelFallbackChain = Collections.unmodifiableList(new ArrayList<ModelId>(
                modelFallbackChain == null ? Collections.<ModelId>emptyList() : modelFallbackChain));
        this.trustedToolIds = Collections.unmodifiableList(new ArrayList<ToolId>(
                trustedToolIds == null ? Collections.<ToolId>emptyList() : trustedToolIds));
    }

    /** 平台初始化写入的默认值；规则出处：模块-感知 §7.2、D08/D10 的常量口径。 */
    public static RuntimePolicy defaults() {
        return new RuntimePolicy(3, 2, 2, 20, 100000, 10000, 20, 0.6, 4000, 60000,
                200, 5, 1, 0.5, 0.1, 0.2, 30, 0.8, 1000000L,
                Collections.<ModelId>emptyList(), Collections.<ToolId>emptyList());
    }

    /**
     * 策略调整：逐项校验后返回**新实例**（调用方负责提交）。
     * 规则出处：模块-感知 §7.2 规则 1/3/4/5/6、模块-应用层 §8.5、技术决策 D11（C_INVALID_VALUE）。
     */
    public RuntimePolicy adjust(java.util.Map<String, Object> changes) {
        int retries = positiveInt(changes, "maxRetries", maxRetries);
        int reflection = positiveInt(changes, "reflectionThreshold", reflectionThreshold);
        // 0 表示"永不重划"，是合法配置（§7.1 规则 5：还能不能重划由外循环判定）
        int replan = nonNegativeInt(changes, "maxReplanGeneration", maxReplanGeneration);
        int rounds = positiveInt(changes, "maxReActRounds", maxReActRounds);
        int summarization = positiveInt(changes, "summarizationTokenThreshold", summarizationTokenThreshold);
        int margin = nonNegativeInt(changes, "inputSafetyMargin", inputSafetyMargin);
        int windowRecords = positiveInt(changes, "contextWindowRecordCount", contextWindowRecordCount);
        double systemRatio = ratio(changes, "systemRecordRatioLimit", systemRecordRatioLimit);
        int truncate = positiveInt(changes, "toolResultTruncateLength", toolResultTruncateLength);
        int subagentTimeout = positiveInt(changes, "subagentExecutionTimeoutMillis",
                subagentExecutionTimeoutMillis);
        int capacity = positiveInt(changes, "experienceCapacity", experienceCapacity);
        int topN = positiveInt(changes, "experienceTopN", experienceTopN);
        int perPlan = positiveInt(changes, "experiencePerPlan", experiencePerPlan);
        double initialConfidence = ratio(changes, "experienceInitialConfidence", experienceInitialConfidence);
        double confidenceStep = ratio(changes, "experienceConfidenceStep", experienceConfidenceStep);
        double retireThreshold = ratio(changes, "experienceRetireThreshold", experienceRetireThreshold);
        int skills = positiveInt(changes, "skillCapacity", skillCapacity);
        double warnRatio = ratio(changes, "budgetWarnRatio", budgetWarnRatio);
        long limitTokens = nonNegativeLong(changes, "budgetLimitTokens", budgetLimitTokens);
        List<ModelId> fallback = modelIds(changes, "modelFallbackChain", modelFallbackChain);
        List<ToolId> trusted = toolIds(changes, "trustedToolIds", trustedToolIds);
        return new RuntimePolicy(retries, reflection, replan, rounds, summarization, margin, windowRecords,
                systemRatio, truncate, subagentTimeout, capacity, topN, perPlan, initialConfidence,
                confidenceStep, retireThreshold, skills, warnRatio, limitTokens, fallback, trusted);
    }

    private static int positiveInt(java.util.Map<String, Object> changes, String key, int current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        int value = toInt(key, raw);
        if (value <= 0) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 必须为正数：" + value);
        }
        return value;
    }

    private static int nonNegativeInt(java.util.Map<String, Object> changes, String key, int current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        int value = toInt(key, raw);
        if (value < 0) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 不能为负：" + value);
        }
        return value;
    }

    private static long nonNegativeLong(java.util.Map<String, Object> changes, String key, long current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        long value;
        try {
            value = Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 不是整数：" + raw);
        }
        if (value < 0) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 不能为负：" + value);
        }
        return value;
    }

    private static double ratio(java.util.Map<String, Object> changes, String key, double current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        double value;
        try {
            value = Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 不是数字：" + raw);
        }
        if (value < 0 || value > 1) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 必须在 [0,1]：" + value);
        }
        return value;
    }

    private static int toInt(String key, Object raw) {
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    key + " 不是整数：" + raw);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelId> modelIds(java.util.Map<String, Object> changes, String key,
                                          List<ModelId> current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        List<ModelId> ids = new ArrayList<ModelId>();
        for (Object item : (List<Object>) raw) {
            ids.add(ModelId.of(String.valueOf(item)));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolId> toolIds(java.util.Map<String, Object> changes, String key,
                                        List<ToolId> current) {
        Object raw = changes.get(key);
        if (raw == null) {
            return current;
        }
        List<ToolId> ids = new ArrayList<ToolId>();
        for (Object item : (List<Object>) raw) {
            ids.add(ToolId.of(String.valueOf(item)));
        }
        return ids;
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

    /** ACTIVE 技能总数上限（含人写、不含 jar 内置）。规则出处：模块-记忆 §8.5、D23-3。 */
    public int skillCapacity() {
        return skillCapacity;
    }

    public double budgetWarnRatio() {
        return budgetWarnRatio;
    }

    /** 会话族总额度（token）：预警比例的**分母**。规则出处：模块-行动 §3.4、§7.5 规则 ③。 */
    public long budgetLimitTokens() {
        return budgetLimitTokens;
    }

    public List<ToolId> trustedToolIds() {
        return trustedToolIds;
    }
}
