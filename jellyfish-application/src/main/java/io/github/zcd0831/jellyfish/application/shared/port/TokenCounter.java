package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.List;

/**
 * 事前计数，用于决定是否折叠；台账仍用厂商 usage。
 */
public interface TokenCounter {

    /** systemPrompt 单独计：它与 messages 在协议里是两段。 */
    int count(String systemPrompt, List<ChatMessage> messages, ModelId modelId);
}
