package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话内一条消息的快照。
 * <p>
 * <b>为什么把 {@code role} / {@code toolCallId} / {@code name} 平铺而不是内嵌一个「消息本体」对象</b>：
 * 这一层就是持久化的载体，平铺后生成的文件是扁平的、可读的、可直接 diff 的；多一层嵌套只是把
 * 同样的字段装进一个只在这里出现的容器里。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionMessageSnapshot {

    /** 消息唯一标识。 */
    private final String messageId;

    /** 消息产生时间戳（epoch millis）。 */
    private final long timestamp;

    /** 消息角色：{@code system} / {@code user} / {@code assistant} / {@code tool}。 */
    private final String role;

    /** 消息文本内容，可为 {@code null}。 */
    private final String content;

    /** 工具结果消息对应的工具调用标识，非工具消息为 {@code null}。 */
    private final String toolCallId;

    /** 工具结果消息对应的工具名，可为 {@code null}。 */
    private final String name;

    /** 本条消息携带的工具调用，无调用时为空列表。 */
    private final List<SessionToolCallSnapshot> toolCalls;

    /** 本条消息的 token 用量，未返回时可为 {@code null}。 */
    private final TokenUsageSnapshot usage;

    /**
     * 构造消息快照。
     *
     * @param messageId  消息唯一标识，不可为空白
     * @param timestamp  消息产生时间戳（epoch millis）
     * @param role       消息角色，不可为空白
     * @param content    消息文本内容，可为 {@code null}
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param name       工具名，可为 {@code null}
     * @param toolCalls  工具调用列表，可为 {@code null}（等价空列表）
     * @param usage      token 用量，可为 {@code null}
     * @throws JellyfishException 消息标识或角色为空白时抛出
     */
    public SessionMessageSnapshot(String messageId, long timestamp, String role, String content,
                                  String toolCallId, String name, List<SessionToolCallSnapshot> toolCalls,
                                  TokenUsageSnapshot usage) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new JellyfishException("message id must not be blank");
        }
        if (role == null || role.trim().isEmpty()) {
            throw new JellyfishException("message role must not be blank");
        }
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.name = name;
        this.toolCalls = copyToolCalls(toolCalls);
        this.usage = usage;
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
     * 获取消息角色。
     *
     * @return 消息角色
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取消息文本内容。
     *
     * @return 消息文本内容，可为 {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取工具调用标识。
     *
     * @return 工具调用标识，可为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，可为 {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * 获取工具调用列表。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<SessionToolCallSnapshot> getToolCalls() {
        return toolCalls;
    }

    /**
     * 获取 token 用量。
     *
     * @return token 用量，可为 {@code null}
     */
    public TokenUsageSnapshot getUsage() {
        return usage;
    }

    @Override
    public String toString() {
        return "SessionMessageSnapshot{id=" + messageId + ", role=" + role + ", toolCalls=" + toolCalls.size() + '}';
    }

    /**
     * 复制工具调用列表并拒绝 {@code null} 元素。
     *
     * @param toolCalls 原始列表，可为 {@code null}
     * @return 不可变列表，保证非 {@code null}
     */
    private static List<SessionToolCallSnapshot> copyToolCalls(List<SessionToolCallSnapshot> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        List<SessionToolCallSnapshot> copy = new ArrayList<SessionToolCallSnapshot>(toolCalls.size());
        for (SessionToolCallSnapshot toolCall : toolCalls) {
            if (toolCall == null) {
                throw new JellyfishException("tool call snapshot must not be null");
            }
            copy.add(toolCall);
        }
        return Collections.unmodifiableList(copy);
    }
}
