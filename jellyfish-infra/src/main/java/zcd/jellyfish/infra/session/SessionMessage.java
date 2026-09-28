package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * 本次模型调用返回的思考过程，仅 assistant 消息可能非 {@code null}。
     * <p>
     * <b>为什么不放进 {@link LlmMessage}</b>：思考是本地展示信息，不回灌给模型；
     * 而 {@code LlmMessage} 是「要发给厂商的请求」模型，添字段就等于把它下发给厂商。
     * 与 {@code usage} 同类，因此都放在会话域这一层。
     */
    private final String thinking;

    /**
     * 工具结果消息的结构化元数据，仅 tool 消息可能非空。
     * <p>
     * <b>为什么不放进 {@link LlmMessage}</b>：与 {@code thinking} 同一条理由——那是要发给厂商的
     * 请求模型，而元数据（退出码、终止原因）是本地展示与审计信息。因此它跟着 {@code thinking}
     * 落在会话域这一层。
     */
    private final Map<String, Object> metadata;

    /**
     * 构造一条会话消息。
     *
     * @param messageId 消息唯一标识，不可为空白
     * @param timestamp 消息产生时间戳（epoch millis）
     * @param message   消息本体，不可为 {@code null}
     * @param usage     token 用量，可为 {@code null}
     * @param thinking  思考过程，可为 {@code null}
     * @param metadata  工具结果元数据，可为 {@code null}（等价空映射）
     * @throws JellyfishException 消息标识为空白，或消息本体为 {@code null} 时抛出
     */
    public SessionMessage(String messageId, long timestamp, LlmMessage message, LlmUsage usage, String thinking,
                          Map<String, Object> metadata) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new JellyfishException("messageId must not be blank");
        }
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.usage = usage;
        this.thinking = thinking;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    /**
     * 构造一条不带元数据的会话消息。
     *
     * @param messageId 消息唯一标识，不可为空白
     * @param timestamp 消息产生时间戳（epoch millis）
     * @param message   消息本体，不可为 {@code null}
     * @param usage     token 用量，可为 {@code null}
     * @param thinking  思考过程，可为 {@code null}
     * @throws JellyfishException 消息标识为空白，或消息本体为 {@code null} 时抛出
     */
    public SessionMessage(String messageId, long timestamp, LlmMessage message, LlmUsage usage, String thinking) {
        this(messageId, timestamp, message, usage, thinking, null);
    }

    /**
     * 构造一条不带思考过程的会话消息。
     *
     * @param messageId 消息唯一标识，不可为空白
     * @param timestamp 消息产生时间戳（epoch millis）
     * @param message   消息本体，不可为 {@code null}
     * @param usage     token 用量，可为 {@code null}
     * @throws JellyfishException 消息标识为空白，或消息本体为 {@code null} 时抛出
     */
    public SessionMessage(String messageId, long timestamp, LlmMessage message, LlmUsage usage) {
        this(messageId, timestamp, message, usage, null);
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
        return of(message, usage, null);
    }

    /**
     * 用当前时刻与自动生成的标识构造一条带 token 用量与思考过程的消息。
     *
     * @param message  消息本体，不可为 {@code null}
     * @param usage    token 用量，可为 {@code null}
     * @param thinking 思考过程，可为 {@code null}
     * @return 会话消息
     */
    public static SessionMessage of(LlmMessage message, LlmUsage usage, String thinking) {
        return new SessionMessage(UUID.randomUUID().toString(), System.currentTimeMillis(), message, usage,
                thinking);
    }

    /**
     * 用当前时刻与自动生成的标识构造一条带工具元数据的消息。
     *
     * @param message  消息本体，不可为 {@code null}
     * @param metadata 工具结果元数据，可为 {@code null}
     * @return 会话消息
     */
    public static SessionMessage ofTool(LlmMessage message, Map<String, Object> metadata) {
        return new SessionMessage(UUID.randomUUID().toString(), System.currentTimeMillis(), message, null,
                null, metadata);
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
     * 获取本次模型调用返回的思考过程。
     *
     * @return 思考过程，仅 assistant 消息可能非 {@code null}
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * 获取工具结果的结构化元数据。
     *
     * @return 元数据，保证非 {@code null}，无元数据时为空映射
     */
    public Map<String, Object> getMetadata() {
        return metadata;
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
