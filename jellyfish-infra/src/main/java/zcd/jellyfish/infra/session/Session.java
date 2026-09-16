package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次会话的运行态聚合根。
 * <p>
 * 持有三层状态：
 * <ol>
 *     <li><b>标识与生命周期</b>：{@code sessionId}（创建后不可变）、{@code createdAt}、
 *     {@code updatedAt}、{@code title}；</li>
 *     <li><b>会话级选择</b>：当前 {@code agentId}、当前 {@code provider} / {@code model}、
 *     当前 {@link PermissionMode}；</li>
 *     <li><b>内容与计量</b>：消息列表、token 累计、压缩摘要（{@code /compact} 的边界与摘要）。</li>
 * </ol>
 * <b>变更方法一律包级可见</b>：外部只能经 {@link SessionManager} 修改会话，事件广播与将来的持久化
 * 派发都挂在那一个入口上；本类只保证「单会话内的原子性与快照安全」，因此它<b>不感知</b>事件通道、
 * 扩展层与配置。
 * <p>
 * 线程安全策略：所有读写都在实例锁内（方法级 {@code synchronized}），读方法返回防御性快照——
 * 调用方拿到的列表不会被后续追加改动，遍历时也不会出现并发修改。会话之间互不影响，跨会话隔离由
 * {@link SessionManager} 的并发映射提供。
 *
 * @author zcd
 */
public final class Session {

    /** 会话唯一标识，创建后不可变。 */
    private final String sessionId;

    /** 会话创建时间戳（epoch millis），创建后不可变。 */
    private final long createdAt;

    /** 消息列表，按追加顺序排列。 */
    private final List<SessionMessage> messages = new ArrayList<SessionMessage>();

    /** 会话标题，可为 {@code null}。 */
    private String title;

    /** 最近一次变更时间戳（epoch millis）。 */
    private long updatedAt;

    /** 当前绑定的 agentId，{@code null} 表示未绑定（fail-open）。 */
    private String agentId;

    /** 当前 provider 名，{@code null} 表示跟随配置默认值。 */
    private String provider;

    /** 当前 model 名，{@code null} 表示跟随配置默认值。 */
    private String model;

    /** 当前权限模式，保证非 {@code null}。 */
    private PermissionMode permissionMode;

    /** token 累计快照，追加时整体替换为新实例。 */
    private SessionUsage usage = SessionUsage.EMPTY;

    /**
     * 压缩摘要，{@code null} 表示从未压缩过。
     * <p>
     * <b>注意它不影响消息列表</b>：压缩是非破坏式的，消息一条不删（屏幕投影、持久化都照旧），
     * 摘要只决定「发给模型的请求从哪里开始」。
     */
    private SessionCompaction compaction;

    /**
     * 构造会话运行态，仅供 {@link SessionManager} 调用。
     *
     * @param sessionId      会话唯一标识
     * @param agentId        初始 agentId，可为 {@code null}
     * @param provider       初始 provider，可为 {@code null}
     * @param model          初始 model，可为 {@code null}
     * @param permissionMode 初始权限模式，{@code null} 按 {@link PermissionMode#NORMAL} 处理
     * @param createdAt      创建时间戳（epoch millis）
     */
    Session(String sessionId, String agentId, String provider, String model,
            PermissionMode permissionMode, long createdAt) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.permissionMode = permissionMode == null ? PermissionMode.NORMAL : permissionMode;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * 由快照还原会话运行态，仅供 {@link SessionManager} 在启动期恢复时调用。
     * <p>
     * 直接写入私有字段而不是走 {@code append}：还原是一次「把已有状态放回去」，
     * 不该产生新的时间戳；走变更路径反而会把历史改掉（例如把 {@code updatedAt}
     * 刷成当前时间）。
     *
     * @param snapshot api 侧的会话快照，不可为 {@code null}
     * @return 还原出的会话运行态
     */
    static Session restore(SessionSnapshot snapshot) {
        Session session = new Session(snapshot.getSessionId(), snapshot.getAgentId(), snapshot.getProvider(),
                snapshot.getModel(), snapshot.getPermissionMode(), snapshot.getCreatedAt());
        session.title = snapshot.getTitle();
        session.updatedAt = snapshot.getUpdatedAt();
        session.usage = SessionSnapshots.toSessionUsage(snapshot.getUsage());
        session.compaction = SessionSnapshots.toCompaction(snapshot.getCompaction());
        for (SessionMessageSnapshot message : snapshot.getMessages()) {
            session.messages.add(SessionSnapshots.toMessage(message));
        }
        return session;
    }

    /**
     * 获取会话唯一标识。
     *
     * @return 会话唯一标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取会话创建时间戳。
     *
     * @return 创建时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取最近一次变更时间戳。
     *
     * @return 变更时间戳（epoch millis），无变更时等于创建时间
     */
    public synchronized long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取会话标题。
     *
     * @return 标题，未设置时为 {@code null}
     */
    public synchronized String getTitle() {
        return title;
    }

    /**
     * 获取当前 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public synchronized String getAgentId() {
        return agentId;
    }

    /**
     * 获取当前 provider 名。
     *
     * @return provider 名，跟随默认时为 {@code null}
     */
    public synchronized String getProvider() {
        return provider;
    }

    /**
     * 获取当前 model 名。
     *
     * @return model 名，跟随默认时为 {@code null}
     */
    public synchronized String getModel() {
        return model;
    }

    /**
     * 获取当前权限模式。
     *
     * @return 权限模式，保证非 {@code null}
     */
    public synchronized PermissionMode getPermissionMode() {
        return permissionMode;
    }

    /**
     * 获取 token 累计快照。
     *
     * @return 累计快照，保证非 {@code null}
     */
    public synchronized SessionUsage getUsage() {
        return usage;
    }

    /**
     * 获取压缩摘要。
     *
     * @return 压缩摘要，从未压缩过时为 {@code null}
     */
    public synchronized SessionCompaction getCompaction() {
        return compaction;
    }

    /**
     * 获取消息条数。
     *
     * @return 消息条数
     */
    public synchronized int size() {
        return messages.size();
    }

    /**
     * 取消息列表的不可修改快照。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public synchronized List<SessionMessage> getMessages() {
        return Collections.unmodifiableList(new ArrayList<SessionMessage>(messages));
    }

    /**
     * 定位一条消息在会话里的下标。
     * <p>
     * 供压缩边界解析用：边界以 {@code messageId} 记账而不是下标（消息只会追加，但快照跨进程传递，
     * 下标的意义依赖当时的列表，换一处就错）。
     *
     * @param messageId 消息标识，可为 {@code null}
     * @return 下标；{@code null} 或不存在的消息返回 {@code -1}
     */
    public synchronized int indexOfMessage(String messageId) {
        if (messageId == null) {
            return -1;
        }
        for (int index = 0; index < messages.size(); index++) {
            if (messageId.equals(messages.get(index).getMessageId())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 追加一条消息并累加 token 用量。
     * <p>
     * 包级可见：只有 {@link SessionManager} 能改会话，事件广播与将来的落盘派发都在那里统一发生。
     *
     * @param message 会话消息
     * @return 追加后的消息条数
     */
    synchronized int append(SessionMessage message) {
        messages.add(message);
        usage = usage.plus(message.getUsage());
        updatedAt = System.currentTimeMillis();
        return messages.size();
    }

    /**
     * 累加一次模型调用的 token 用量（不追加消息）。
     * <p>
     * 给「不产生会话消息的调用」留的口子：{@code /compact} 的摘要调用花的是真金白银的 token，
     * 但它不该在对话里留下一条消息——把它混进消息列表会让屏幕投影多出一条谁也没说过的话。
     *
     * @param callUsage 一次调用的用量，可为 {@code null}（厂商未返回时只累加调用次数）
     */
    synchronized void recordUsage(LlmUsage callUsage) {
        usage = usage.plus(callUsage);
    }

    /**
     * 设置压缩摘要。
     *
     * @param compaction 压缩摘要，可为 {@code null}（表示清除）
     */
    synchronized void setCompaction(SessionCompaction compaction) {
        this.compaction = compaction;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置会话标题。
     *
     * @param title 标题，可为 {@code null}
     */
    synchronized void setTitle(String title) {
        this.title = title;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置当前 agentId。
     *
     * @param agentId agentId，可为 {@code null}（表示解绑）
     */
    synchronized void setAgentId(String agentId) {
        this.agentId = agentId;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置当前 provider / model。
     *
     * @param provider provider 名，可为 {@code null}（表示跟随默认）
     * @param model    model 名，可为 {@code null}（表示跟随默认）
     */
    synchronized void setModel(String provider, String model) {
        this.provider = provider;
        this.model = model;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置当前权限模式。
     *
     * @param permissionMode 权限模式，{@code null} 按 {@link PermissionMode#NORMAL} 处理
     */
    synchronized void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.NORMAL : permissionMode;
        this.updatedAt = System.currentTimeMillis();
    }
}
