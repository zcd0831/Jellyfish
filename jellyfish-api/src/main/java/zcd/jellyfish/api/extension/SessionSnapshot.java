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

    /** 会话消息列表，可为 {@code null}（等价空列表）。 */
    private final List<SessionMessageSnapshot> messages;

    /** 会话累计 token 用量，可为 {@code null}（按零用量处理）。 */
    private final SessionUsageSnapshot usage;

    /** 会话的压缩摘要，从未压缩过时为 {@code null}。 */
    private final SessionCompactionSnapshot compaction;

    /**
     * 会话种类。
     * <p>
     * <b>它就是「是不是子代理会话」的判定来源</b>（{@code parentSessionId} 只是追溯信息）。
     * 老快照没有这个字段，恢复时按 {@code parentSessionId} 是否存在补出来，见
     * {@link SessionKind}。
     */
    private final SessionKind kind;

    /** 派生该会话的父会话标识，根会话为 {@code null}。 */
    private final String parentSessionId;

    /** 分支点消息标识（含），非分支会话为 {@code null}。 */
    private final String forkPointMessageId;

    /** 插件挂在会话上的扩展条目，无条目时为空列表。 */
    private final List<SessionExtensionEntry> extensionEntries;

    /**
     * 构造会话快照。
     * <p>
     * <b>为什么这里只有唯一一个构造器</b>：本类型靠 Jackson 的「隐式属性构造器」反序列化
     * （{@code -parameters} + {@code ParameterNamesModule}，见 {@code SnapshotJson}），
     * 而 Jackson 只在「恰好一个可见构造器」时才认它为隐式创建器；多出一个重载会让整个快照类型
     * <b>直接反序列化失败</b>，代价是整段会话读不回来。因此新增字段时不要加「兼容构造器」，
     * 兼容入口请改用静态工厂（见
     * {@link #of(String, long, long, String, String, String, String, List, SessionUsageSnapshot)}）。
     *
     * @param sessionId      会话标识，不可为空白
     * @param createdAt      创建时间戳（epoch millis）
     * @param updatedAt      最后变更时间戳（epoch millis）
     * @param title          标题，可为 {@code null}
     * @param agentId        agentId，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          model 名，可为 {@code null}
     * @param messages       消息列表，可为 {@code null}
     * @param usage          累计用量，可为 {@code null}
     * @param compaction     压缩摘要，可为 {@code null}
     * @param kind           会话种类，可为 {@code null}（按 {@link SessionKind#NORMAL} 处理）
     * @param parentSessionId 派生该会话的父会话标识，可为 {@code null}
     * @param forkPointMessageId 分支点消息标识，可为 {@code null}
     * @param extensionEntries 扩展条目，可为 {@code null}（等价空列表）
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public SessionSnapshot(String sessionId, long createdAt, long updatedAt, String title, String agentId,
                           String provider, String model,
                           List<SessionMessageSnapshot> messages, SessionUsageSnapshot usage,
                           SessionCompactionSnapshot compaction, SessionKind kind, String parentSessionId,
                           String forkPointMessageId, List<SessionExtensionEntry> extensionEntries) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("session id must not be blank");
        }
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.title = title;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.messages = copyMessages(messages);
        this.usage = usage;
        this.compaction = compaction;
        this.kind = kind;
        this.parentSessionId = parentSessionId;
        this.forkPointMessageId = forkPointMessageId;
        this.extensionEntries = copyEntries(extensionEntries);
    }

    /**
     * 构造不含压缩摘要、会话种类与扩展条目的会话快照（旧签名的兼容入口）。
     * <p>
     * 与构造器等价，只是不能写成构造器重载（见
     * {@link #SessionSnapshot(String, long, long, String, String, String, String, List,
     * SessionUsageSnapshot, SessionCompactionSnapshot, SessionKind, String, String, List)}）。
     * <p>
     * <b>缺的那四个字段按「普通根会话、无扩展条目」补</b>：老快照没有它们，这就是它们的含义——
     * 分支会话不会走到这条入口，因为它总是带着 {@link SessionKind#FORKED} 从恢复路径回来。
     *
     * @param sessionId      会话标识，不可为空白
     * @param createdAt      创建时间戳（epoch millis）
     * @param updatedAt      最后变更时间戳（epoch millis）
     * @param title          标题，可为 {@code null}
     * @param agentId        agentId，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          model 名，可为 {@code null}
     * @param messages       消息列表，可为 {@code null}
     * @param usage          累计用量，可为 {@code null}
     * @return 不含压缩摘要的会话快照
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static SessionSnapshot of(String sessionId, long createdAt, long updatedAt, String title,
                                     String agentId, String provider, String model,
                                     List<SessionMessageSnapshot> messages,
                                     SessionUsageSnapshot usage) {
        return new SessionSnapshot(sessionId, createdAt, updatedAt, title, agentId, provider, model,
                messages, usage, null, SessionKind.NORMAL, null, null, null);
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

    /**
     * 获取会话的压缩摘要。
     *
     * @return 压缩摘要，从未压缩过时为 {@code null}
     */
    public SessionCompactionSnapshot getCompaction() {
        return compaction;
    }

    @Override
    public String toString() {
        return "SessionSnapshot{sessionId=" + sessionId + ", kind=" + getKind()
                + ", messages=" + messages.size() + '}';
    }

    /**
     * 获取会话种类。
     * <p>
     * <b>缺字段时按「是否为子代理会话」补</b>：老快照里根本没有 {@code kind}，而那时
     * {@code parentSessionId} 非空只可能是子代理会话（分支能力是后加的）。反过来把
     * 空值当 {@code NORMAL} 会让一个子代理会话被当成普通会话<b>落盘并进列表</b>，
     * 而那正是「一字段两用」带来的静默数据丢失。
     *
     * @return 会话种类，保证非 {@code null}
     */
    public SessionKind getKind() {
        if (kind != null) {
            return kind;
        }
        return parentSessionId == null ? SessionKind.NORMAL : SessionKind.EPHEMERAL;
    }

    /**
     * 获取派生该会话的父会话标识。
     * <p>
     * <b>它只是追溯信息，不是判定依据</b>：判定这个会话是不是子代理会话请用 {@link #getKind()}。
     *
     * @return 父会话标识；根会话为 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取分支点消息标识。
     *
     * @return 分支点消息标识，非分支会话为 {@code null}
     */
    public String getForkPointMessageId() {
        return forkPointMessageId;
    }

    /**
     * 获取扩展条目。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<SessionExtensionEntry> getExtensionEntries() {
        return extensionEntries;
    }

    /**
     * 复制扩展条目列表并拒绝 {@code null} 元素。
     *
     * @param entries 原始列表，可为 {@code null}
     * @return 不可变列表，保证非 {@code null}
     */
    private static List<SessionExtensionEntry> copyEntries(List<SessionExtensionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<SessionExtensionEntry> copy = new ArrayList<SessionExtensionEntry>(entries.size());
        for (SessionExtensionEntry entry : entries) {
            if (entry == null) {
                throw new JellyfishException("session extension entry must not be null");
            }
            copy.add(entry);
        }
        return Collections.unmodifiableList(copy);
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
