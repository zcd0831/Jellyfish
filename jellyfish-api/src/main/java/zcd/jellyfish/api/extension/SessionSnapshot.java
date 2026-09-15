package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个会话的完整快照：持久化扩展点承载的载荷。
 * <p>
 * <b>为什么需要它</b>：内核的 {@code Session} / {@code LlmMessage} 住在 {@code jellyfish-infra}，
 * 插件只看得到 {@code jellyfish-api}。会话持久化是插件的职责，那么「要落盘的会话长什么样」
 * 就必须是一份 api 侧的契约——否则插件除了把一个不透明的字节块搬来搬去什么也做不了，
 * 而「加密、迁移、搜索、同步到数据库」恰恰是持久化插件存在的理由。
 * <p>
 * <b>与内核模型的关系</b>：这是一份<b>投影</b>，不是第二份真相。内核在变更会话时生成快照交给插件，
 * 插件原样存下；回放时内核把快照还原成会话。字段与内核模型一一对应，任何一侧新增字段都需要
 * 同步过来——往返测试（capture → restore → capture 必须相等）就是这条约束的护栏。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionSnapshot {

    /** 会话标识。 */
    private final String sessionId;

    /** 会话创建时间戳（epoch millis）。 */
    private final long createdAt;

    /** 最后一次变更时间戳（epoch millis）。 */
    private final long updatedAt;

    /** 会话标题，可为 {@code null}。 */
    private final String title;

    /** 会话绑定的 agentId，可为 {@code null}（未绑定）。 */
    private final String agentId;

    /** 会话当前 provider 名，{@code null} 表示跟随默认。 */
    private final String provider;

    /** 会话当前 model 名，{@code null} 表示跟随默认。 */
    private final String model;

    /** 会话当前权限模式，不可为 {@code null}。 */
    private final PermissionMode permissionMode;

    /** 会话消息列表，可为 {@code null}（等价空列表）。 */
    private final List<SessionMessageSnapshot> messages;

    /** 会话累计 token 用量，可为 {@code null}（按零用量处理）。 */
    private final SessionUsageSnapshot usage;

    /**
     * 构造会话快照。
     *
     * @param sessionId      会话标识，不可为空白
     * @param createdAt      创建时间戳（epoch millis）
     * @param updatedAt      最后变更时间戳（epoch millis）
     * @param title          标题，可为 {@code null}
     * @param agentId        agentId，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          model 名，可为 {@code null}
     * @param permissionMode 权限模式，不可为 {@code null}
     * @param messages       消息列表，可为 {@code null}
     * @param usage          累计用量，可为 {@code null}
     * @throws JellyfishException 会话标识为空白或权限模式为 {@code null} 时抛出
     */
    public SessionSnapshot(String sessionId, long createdAt, long updatedAt, String title, String agentId,
                           String provider, String model, PermissionMode permissionMode,
                           List<SessionMessageSnapshot> messages, SessionUsageSnapshot usage) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("session id must not be blank");
        }
        if (permissionMode == null) {
            throw new JellyfishException("permission mode must not be null");
        }
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.title = title;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.permissionMode = permissionMode;
        this.messages = copyMessages(messages);
        this.usage = usage;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取创建时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取最后变更时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取会话标题。
     *
     * @return 标题，可为 {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取会话绑定的 agentId。
     *
     * @return agentId，可为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取会话当前 provider 名。
     *
     * @return provider 名，可为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取会话当前 model 名。
     *
     * @return model 名，可为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取会话当前权限模式。
     *
     * @return 权限模式，保证非 {@code null}
     */
    public PermissionMode getPermissionMode() {
        return permissionMode;
    }

    /**
     * 获取会话消息列表。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<SessionMessageSnapshot> getMessages() {
        return messages;
    }

    /**
     * 获取会话累计 token 用量。
     *
     * @return 累计用量，可为 {@code null}
     */
    public SessionUsageSnapshot getUsage() {
        return usage;
    }

    @Override
    public String toString() {
        return "SessionSnapshot{sessionId=" + sessionId + ", messages=" + messages.size() + '}';
    }

    /**
     * 复制消息列表并拒绝 {@code null} 元素。
     *
     * @param messages 原始列表，可为 {@code null}
     * @return 不可变列表，保证非 {@code null}
     */
    private static List<SessionMessageSnapshot> copyMessages(List<SessionMessageSnapshot> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<SessionMessageSnapshot> copy = new ArrayList<SessionMessageSnapshot>(messages.size());
        for (SessionMessageSnapshot message : messages) {
            if (message == null) {
                throw new JellyfishException("session message snapshot must not be null");
            }
            copy.add(message);
        }
        return Collections.unmodifiableList(copy);
    }
}
