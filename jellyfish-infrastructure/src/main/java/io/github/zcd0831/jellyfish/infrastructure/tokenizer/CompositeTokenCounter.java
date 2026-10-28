package io.github.zcd0831.jellyfish.infrastructure.tokenizer;

import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.List;

/**
 * 策略链：jtokkit 优先 → 估算兜底；任一策略异常即降级到下一个。
 */
public final class CompositeTokenCounter implements TokenCounter {

    private final JtokkitStrategy jtokkit;
    private final EstimatorStrategy estimator;

    public CompositeTokenCounter() {
        this(new JtokkitStrategy(), new EstimatorStrategy());
    }

    CompositeTokenCounter(JtokkitStrategy jtokkit, EstimatorStrategy estimator) {
        this.jtokkit = jtokkit;
        this.estimator = estimator;
    }

    @Override
    public int count(String systemPrompt, List<ChatMessage> messages, ModelId modelId) {
        if (jtokkit.supports(modelId.getValue())) {
            try {
                return jtokkit.count(systemPrompt, messages);
            } catch (RuntimeException ignored) {
                // 分词器不认这段文本（或版本不支持）时直接退到估算，不阻断调用
            }
        }
        return estimator.count(systemPrompt, messages);
    }
}
