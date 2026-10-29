package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.model.ModelCapability;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolSchemaSnapshot;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型调用入参：用量随请求下行，执行器不回查会话。
 * 规则出处：05-action §5.6、§5.9.2。
 */
public final class ModelInvocationRequest {

    private final SessionId sessionId;
    private final ModelId targetModelId;
    private final String systemPrompt;
    private final List<ChatMessage> messages;
    private final List<ToolSchemaSnapshot> tools;
    private final int estimatedPromptTokens;
    private final ModelCapability requiredCapability;

    public ModelInvocationRequest(SessionId sessionId, ModelId targetModelId, String systemPrompt,
                                  List<ChatMessage> messages, List<ToolSchemaSnapshot> tools,
                                  int estimatedPromptTokens, ModelCapability requiredCapability) {
        this.sessionId = sessionId;
        this.targetModelId = targetModelId;
        this.systemPrompt = systemPrompt;
        this.messages = immutable(messages);
        this.tools = immutable(tools);
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.requiredCapability = requiredCapability;
    }

    /** 没有会话归属的调用（测试、起标题这类轮次外短调用）：永远不参与终止。 */
    public ModelInvocationRequest(ModelId targetModelId, String systemPrompt, List<ChatMessage> messages,
                                  List<ToolSchemaSnapshot> tools, int estimatedPromptTokens,
                                  ModelCapability requiredCapability) {
        this(null, targetModelId, systemPrompt, messages, tools, estimatedPromptTokens, requiredCapability);
    }

    /** 会话归属：执行器据此取该会话的终止信号，不回头查 Session（§5.9.2 的口径）。 */
    public SessionId sessionId() {
        return sessionId;
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(
                source == null ? Collections.<T>emptyList() : source));
    }

    public ModelId targetModelId() {
        return targetModelId;
    }

    /** 系统提示词独立于 messages；落协议时由适配器决定放哪个字段。 */
    public String systemPrompt() {
        return systemPrompt;
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    /** 会话开启时冻结的工具 schema 快照；执行器不得现查目录。 */
    public List<ToolSchemaSnapshot> tools() {
        return tools;
    }

    public int estimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public ModelCapability requiredCapability() {
        return requiredCapability;
    }
}
