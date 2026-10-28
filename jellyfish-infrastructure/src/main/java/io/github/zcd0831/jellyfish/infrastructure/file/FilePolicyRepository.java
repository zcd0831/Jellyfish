package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * settings.json 是策略的唯一来源；不存在时写默认值。
 * 规则出处：09-infrastructure §9.4。
 */
public final class FilePolicyRepository implements PolicyRepository {

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    public FilePolicyRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    @Override
    public RuntimePolicy load() {
        Path file = dataDirectory.settingsFile();
        if (!Files.exists(file)) {
            RuntimePolicy defaults = RuntimePolicy.defaults();
            save(defaults);
            return defaults;
        }
        try {
            PolicyDto dto = codec.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), PolicyDto.class);
            return dto.toDomain();
        } catch (IOException e) {
            throw new UncheckedIOException("读取策略失败：" + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("settings.json 解析失败，请修正后重启：" + file, e);
        }
    }

    @Override
    public void save(RuntimePolicy policy) {
        AtomicFiles.write(dataDirectory.settingsFile(), codec.write(PolicyDto.from(policy)));
    }

    /** 纯 JSON 配置形态。 */
    public static class PolicyDto {

        public int maxRetries;
        public int reflectionThreshold;
        public int maxReplanGeneration;
        public int maxReActRounds;
        public int summarizationTokenThreshold;
        public int inputSafetyMargin;
        public int collapseRetainRecordCount;
        public int toolResultTruncateLength;
        public int subagentExecutionTimeoutMillis;
        public int experienceCapacity;
        public int experienceTopN;
        public int experiencePerPlan;
        public double experienceInitialConfidence;
        public double experienceConfidenceStep;
        public double experienceRetireThreshold;
        public int skillCapacity;
        public double budgetWarnRatio;
        public long budgetLimitTokens;
        public List<String> modelFallbackChain = new ArrayList<String>();
        public List<String> trustedToolIds = new ArrayList<String>();

        static PolicyDto from(RuntimePolicy policy) {
            PolicyDto dto = new PolicyDto();
            dto.maxRetries = policy.maxRetries();
            dto.reflectionThreshold = policy.reflectionThreshold();
            dto.maxReplanGeneration = policy.maxReplanGeneration();
            dto.maxReActRounds = policy.maxReActRounds();
            dto.summarizationTokenThreshold = policy.summarizationTokenThreshold();
            dto.inputSafetyMargin = policy.inputSafetyMargin();
            dto.collapseRetainRecordCount = policy.collapseRetainRecordCount();
            dto.toolResultTruncateLength = policy.toolResultTruncateLength();
            dto.subagentExecutionTimeoutMillis = policy.subagentExecutionTimeoutMillis();
            dto.experienceCapacity = policy.experienceCapacity();
            dto.experienceTopN = policy.experienceTopN();
            dto.experiencePerPlan = policy.experiencePerPlan();
            dto.experienceInitialConfidence = policy.experienceInitialConfidence();
            dto.experienceConfidenceStep = policy.experienceConfidenceStep();
            dto.experienceRetireThreshold = policy.experienceRetireThreshold();
            dto.skillCapacity = policy.skillCapacity();
            dto.budgetWarnRatio = policy.budgetWarnRatio();
            dto.budgetLimitTokens = policy.budgetLimitTokens();
            for (ModelId modelId : policy.modelFallbackChain()) {
                dto.modelFallbackChain.add(modelId.getValue());
            }
            for (ToolId toolId : policy.trustedToolIds()) {
                dto.trustedToolIds.add(toolId.getValue());
            }
            return dto;
        }

        RuntimePolicy toDomain() {
            List<ModelId> fallback = new ArrayList<ModelId>();
            for (String id : modelFallbackChain) {
                fallback.add(ModelId.of(id));
            }
            List<ToolId> trusted = new ArrayList<ToolId>();
            for (String id : trustedToolIds) {
                trusted.add(ToolId.of(id));
            }
            // 旧 settings.json 的键已被忽略；缺失（0）时退回默认值，避免写出一个非法策略
            int retain = collapseRetainRecordCount > 0
                    ? collapseRetainRecordCount : RuntimePolicy.defaults().collapseRetainRecordCount();
            return new RuntimePolicy(maxRetries, reflectionThreshold, maxReplanGeneration, maxReActRounds,
                    summarizationTokenThreshold, inputSafetyMargin, retain,
                    toolResultTruncateLength, subagentExecutionTimeoutMillis,
                    experienceCapacity, experienceTopN, experiencePerPlan, experienceInitialConfidence,
                    experienceConfidenceStep, experienceRetireThreshold, skillCapacity, budgetWarnRatio,
                    budgetLimitTokens, fallback, trusted);
        }
    }
}
