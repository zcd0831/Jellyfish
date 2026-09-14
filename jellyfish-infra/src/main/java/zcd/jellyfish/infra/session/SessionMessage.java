package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.Objects;
import java.util.UUID;

/**
 * 会话内的一条消息：会话域元信息 + 厂商无关的消息本体。
 * <p>
 * 为什么不直接往 {@link Session} 里放 {@link LlmMessage}：消息 id、产生时间、token 用量属于
 * <b>会话域</b>，持久化回放、UI 展示与指标聚合都需要它们；而 {@link LlmMessage} 是各厂商接口
 * 无关的统一模型，掺入会话元信息会污染它的语义边界。因此这里包一层，两个模型各自保持干净。
 * <p>
 * 不可变：所有字段 final、无 setter，可安全地在多线程间传递。
 *
 * @author zcd
 */
public final class SessionMessage {

    /** 消息唯一标识。 */
    private final String messageId;

    /** 消息产生时间戳（epoch millis）。 */
    private final long timestamp;

    /** 消息本体。 */
    private final LlmMessage message;

    /** 本次模型调用返回的 token 用量，仅 assistant 消息可能非 {@code null}。 */
    private final LlmUsage usage;

    /**
     * 构造一条会话消息。
     *
     * @param messageId 消息唯一标识，不可为空白
     * @param timestamp 消息产生时间戳（epoch millis）
     * @param message   消息本体，不可为 {@code null}
     * @param usage     token 用量，可为 {@code null}
     * @throws JellyfishException 消息标识为空白，或消息本体为 {@code null} 时抛出
     */
    public SessionMessage(String messageId, long timestamp, LlmMessage message, LlmUsage usage) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new JellyfishException("messageId must not be blank");
        }
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.usage = usage;
    }

    /**
     * 用当前时刻与自动生成的标识构造一条消息。
     *
     * @param message 消息本体，不可为 {@code null}
     * @return 会话消息
     */
    public static SessionMessage of(LlmMessage message) {
        return of(message, null);
    }

    /**
     * 用当前时刻与自动生成的标识构造一条带 token 用量的消息。
     *
     * @param message 消息本体，不可为 {@code null}
     * @param usage   token 用量，可为 {@code null}
     * @return 会话消息
     */
    public static SessionMessage of(LlmMessage message, LlmUsage usage) {
        return new SessionMessage(UUID.randomUUID().toString(), System.currentTimeMillis(), message, usage);
    }

    /**
     * 获取消息唯一标识。
     *
     * @return 消息唯一标识
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * 获取消息产生时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 获取消息本体。
     *
     * @return 消息本体，保证非 {@code null}
     */
    public LlmMessage getMessage() {
        return message;
    }

    /**
     * 获取本次模型调用的 token 用量。
     *
     * @return token 用量，仅 assistant 消息可能非 {@code null}
     */
    public LlmUsage getUsage() {
        return usage;
    }

    /**
     * 获取消息角色。
     * <p>
     * 投影而非独立字段：角色只有一个真相（{@link LlmMessage}），存两份迟早不一致。
     *
     * @return 消息角色
     */
    public String getRole() {
        return message.getRole();
    }
}
