package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;

import java.util.EnumMap;
import java.util.Map;

/**
 * 协议 → 适配器的唯一查表处：三种协议各登记一次，新增协议只动这里。
 * <p>此前分派散在 {@code ModelInvoker.adapterFor} 的三个 if 里，加一种协议要改
 * "取值方法 + 三个字段 + 三个分支"四处；收成表之后 `ModelInvoker` 只问一句
 * "这个协议是谁"。
 * 规则出处：09-infrastructure §9.11（模型调用是唯一出口，协议差异留在适配器里）。
 */
final class ProtocolRegistry {

    private final Map<ChatProtocol, ChatProtocolAdapter> adapters =
            new EnumMap<ChatProtocol, ChatProtocolAdapter>(ChatProtocol.class);

    /** 生产：内置三套协议。未登记 = 未实现，由调用方按「不可用」降级。 */
    ProtocolRegistry() {
        adapters.put(ChatProtocol.OPENAI_CHAT, new ChatCompletionsProtocol());
        adapters.put(ChatProtocol.ANTHROPIC_MESSAGES, new AnthropicMessagesProtocol());
        adapters.put(ChatProtocol.GEMINI, new GeminiProtocol());
    }

    /** 供应商声明的协议拿适配器；没登记就返回 null（不是抛错：降级链要继续试下一个模型）。 */
    ChatProtocolAdapter of(Provider provider) {
        return provider == null || provider.protocol() == null ? null : adapters.get(provider.protocol());
    }
}
