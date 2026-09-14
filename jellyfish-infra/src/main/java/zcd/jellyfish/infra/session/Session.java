package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.extension.PermissionMode;

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
 *     <li><b>内容与计量</b>：消息列表、token 累计与待办列表。</li>
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

    /** 会话级待办项列表，按追加顺序排列。 */
    private final List<PendingTodo> todos = new ArrayList<PendingTodo>();

    /** 待办编号自增序列；清空待办后不复用旧编号。 */
    private long todoSequence;

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

    /**
     * 取待办列表的不可修改快照。
     * <p>
     * 读路径与 {@link #getMessages()} 同口径：公开但返回防御性快照，调用方拿到的列表不会被后续追加改动。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public synchronized List<PendingTodo> getTodos() {
        return Collections.unmodifiableList(new ArrayList<PendingTodo>(todos));
    }

    /**
     * 追加一条待办并分配会话内自增编号。
     * <p>
     * 包级可见：只有 {@link SessionManager} 能改会话，将来的落盘派发都挂在那一个入口上。
     *
     * @param content 待办内容
     * @return 新增的待办项
     */
    synchronized PendingTodo addTodo(String content) {
        PendingTodo todo = PendingTodo.pending(String.valueOf(++todoSequence), content,
                System.currentTimeMillis());
        todos.add(todo);
        updatedAt = System.currentTimeMillis();
        return todo;
    }

    /**
     * 按编号标记待办为已完成。
     * <p>
     * 幂等：已完成的项再次标记不算失败，返回 {@code false} 让调用方区分「没这条」与「本条已完成」。
     *
     * @param todoId 待办编号，可为 {@code null}
     * @return 确实发生状态流转返回 {@code true}
     */
    synchronized boolean completeTodo(String todoId) {
        if (todoId == null) {
            return false;
        }
        for (int i = 0; i < todos.size(); i++) {
            PendingTodo todo = todos.get(i);
            if (todoId.equals(todo.getId())) {
                if (todo.isDone()) {
                    return false;
                }
                todos.set(i, todo.done());
                updatedAt = System.currentTimeMillis();
                return true;
            }
        }
        return false;
    }

    /**
     * 清空全部待办。
     *
     * @return 被清空的待办条数
     */
    synchronized int clearTodos() {
        int size = todos.size();
        if (size > 0) {
            todos.clear();
            updatedAt = System.currentTimeMillis();
        }
        return size;
    }
}
