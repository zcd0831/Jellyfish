package io.github.zcd0831.jellyfish.infrastructure.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;

import java.util.List;

/**
 * 本地分词器：对 OpenAI 系是精确值，对其它是近似，偏差由校准吸收。
 * 规则出处：技术决策 D08 第 2 节（jtokkit 优先）。
 */
public final class JtokkitStrategy {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public boolean supports(String modelId) {
        return true;
    }

    public int count(String systemPrompt, List<ChatMessage> messages) {
        StringBuilder text = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        for (ChatMessage message : messages) {
            if (message.content() != null) {
                text.append('\n').append(message.content());
            }
            if (message.toolCallId() != null) {
                text.append('\n').append(message.toolCallId());
            }
        }
        return encoding.countTokens(text.toString());
    }
}
