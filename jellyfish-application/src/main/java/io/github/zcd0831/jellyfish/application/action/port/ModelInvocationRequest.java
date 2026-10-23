package io.github.zcd0831.jellyfish.application.action.port;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.action.model.ModelCapability;

/**
 * 模型调用入参：用量随请求下行，执行器不回查会话。
 * 规则出处：模块-行动 §8.2、技术决策 D06。
 */
public final class ModelInvocationRequest {

    private final ModelId targetModelId;
    private final List<String> messages;
    private final int estimatedPromptTokens;
    private final ModelCapability requiredCapability;

    public ModelInvocationRequest(ModelId targetModelId, List<String> messages,
                                  int estimatedPromptTokens, ModelCapability requiredCapability) {
        this.targetModelId = targetModelId;
        this.messages = messages;
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.requiredCapability = requiredCapability;
    }

    public ModelId targetModelId() {
        return targetModelId;
    }

    public List<String> messages() {
        return messages;
    }

    public int estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public ModelCapability requiredCapability() {
        return requiredCapability;
    }
}
