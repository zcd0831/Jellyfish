package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.model.ModelCapability;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型调用入参：用量随请求下行，执行器不回查会话。
 * 规则出处：模块-行动 §5、§8.2、技术决策 D06/D16。
 */
public final class ModelInvocationRequest {

    private final ModelId targetModelId;
    private final String systemPrompt;
    private final List<ChatMessage> messages;
    private final int estimatedPromptTokens;
    private final ModelCapability requiredCapability;

    public ModelInvocationRequest(ModelId targetModelId, String systemPrompt, List<ChatMessage> messages,
                                  int estimatedPromptTokens, ModelCapability requiredCapability) {
        this.targetModelId = targetModelId;
        this.systemPrompt = systemPrompt;
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(
                messages == null ? Collections.<ChatMessage>emptyList() : messages));
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.requiredCapability = requiredCapability;
    }

    public ModelId targetModelId() {
        return targetModelId;
    }

    /** 系统提示词独立于 messages；落协议时由适配器决定放哪个字段（D16）。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public int estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public ModelCapability requiredCapability() {
        return requiredCapability;
    }
}
