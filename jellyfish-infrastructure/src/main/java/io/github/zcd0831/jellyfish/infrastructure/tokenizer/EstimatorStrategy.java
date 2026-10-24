package io.github.zcd0831.jellyfish.infrastructure.tokenizer;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.domain.shared.TokenEstimator;

import java.util.List;

/**
 * 兜底估算：CJK 按 1 token/字，其它按 4 字符/token，每条消息 +4。
 * **宁可高估**——高估只是多折叠一次，低估会撞 API 400。
 * 规则出处：技术决策 D08 第 2 节。
 */
public final class EstimatorStrategy implements TokenEstimator {

    private static final int PER_MESSAGE_OVERHEAD = 4;

    public int count(String systemPrompt, List<ChatMessage> messages) {
        double total = textTokens(systemPrompt);
        for (ChatMessage message : messages) {
            total += PER_MESSAGE_OVERHEAD + textTokens(message.content());
        }
        return (int) Math.ceil(total);
    }

    @Override
    public int estimate(String text) {
        return (int) Math.ceil(textTokens(text));
    }

    private static double textTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + other / 4.0;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 常用汉字
                || (c >= 0x3040 && c <= 0x30FF)   // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF)   // 韩文
                || (c >= 0xFF00 && c <= 0xFFEF);  // 全角符号
    }
}
