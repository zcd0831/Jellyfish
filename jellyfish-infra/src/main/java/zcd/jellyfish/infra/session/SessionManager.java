package zcd.jellyfish.infra.session;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.SessionMessageAppendedEvent;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话域服务：会话隔离、消息列表、token 统计，以及会话内当前 agentId / 当前模型 / 当前权限模式的
 * 会话级切换。
 * <p>
 * 与 {@code ModelManager} / {@code AgentManager} 的差异：那两者是「配置驱动的只读索引 + 装载事件」，
 * 本类是<b>可变运行态</b>——不读配置、不建索引、不广播装载事件，只维护「sessionId → Session」表与一个
 * 进程内的当前会话指针。
 * <p>
 * <b>唯一变更入口</b>：所有会话运行态变更（追加消息 / 改标题 / 绑 agent / 切模型 / 切权限模式）都必须
 * 经本类。因为这些变更要连带做两件横切的事——广播通知、将来的同步扩展点落盘（架构图
 * {@code SessionMgr ==> ExtReg}）——集中在一处才不会每个调用点各写一遍。
 * <p>
 * <b>不持有全局模型状态</b>：当前模型是会话字段，本类只做读写转发；解析与路由仍归 {@code ModelManager}，
 * 因此同一进程内的不同会话可以各用各的模型。
 * <p>
 * <b>本轮不做</b>：
 * <ul>
 *     <li>持久化：由插件经{@code ExtensionRegistry} 同步扩展点完成，落点见 {@link #appendMessage} 的 TODO；
 *     待办变更同样属于将来要落盘的范围，目前仅内存态。</li>
 *     <li>上下文裁剪与 token 预算：归 {@code core/prompt}，本类只做计量；</li>
 * </ul>
 * <b>已落地</b>：会话级待办运行态（{@code /todo} 命令与 {@code core/prompt} 的注入都读这里），
 * 注入发生在构建上下文时且不写回消息历史（见 session 方案 §4.5）。
 *
 * @author zcd
 */
@Singleton
public class SessionManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** agent 门面，仅用于会话创建时解析默认 agent。 */
    private final AgentManager agentManager;

    /** 通知发布入口，用于广播会话生命周期通知。 */
    private final EventPublisher events;

    /** 会话表：sessionId → 会话运行态。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    /** 当前会话标识；{@code null} 表示当前没有会话。 */
    private final AtomicReference<String> currentSessionId = new AtomicReference<String>();

    /**
     * 构造会话域服务。
     *
     * @param agentManager agent 门面，用于解析会话创建时的默认 agent
     * @param events       通知发布入口
     */
    @Inject
    public SessionManager(AgentManager agentManager, EventPublisher events) {
        this.agentManager = agentManager;
        this.events = events;
    }

    /**
     * 创建一个会话并返回其运行态。
     * <p>
     * {@code agentId} 为空白时按 {@link AgentManager#resolveDefault()} 绑定默认 agent，结果<b>可能仍为
     * {@code null}</b>——「一个 agent 都没配」是合法状态（全员 fail-open），不应让会话创建失败。
     * <p>
     * {@code provider} / {@code model} <b>不做</b>默认值解析：它们只影响路由，{@code null} 表示「跟随默认」，
     * 由调用点在真正发起 LLM 调用时用 {@code ModelManager.resolveDefault()} 解析——那个方法在没有模型时
     * 抛异常，若在这里调用会把「没配模型」拖成会话创建失败。
     * <p>
     * <b>本方法不自动把新会话设为当前会话</b>：并发创建不应互相抢占当前指针，切换由调用点显式
     * {@link #switchTo(String)} 完成。
     *
     * @param agentId        agent 标识，可为空白（按默认 agent 绑定）
     * @param provider       provider 名，可为 {@code null}
     * @param model          model 名，可为 {@code null}
     * @param permissionMode 权限模式，可为 {@code null}（按 NORMAL 处理）
     * @return 新建的会话运行态
     */
    public Session create(String agentId, String provider, String model, PermissionMode permissionMode) {
        String boundAgentId = StringUtils.isBlank(agentId) ? resolveDefaultAgentId() : agentId;
        Session session = new Session(UUID.randomUUID().toString(), boundAgentId, provider, model,
                permissionMode, System.currentTimeMillis());
        sessions.put(session.getSessionId(), session);
        publish(new SessionCreatedEvent(boundAgentId, session.getSessionId()));
        return session;
    }

    /**
     * 按默认 agent 与常规权限模式创建一个会话。
     *
     * @return 新建的会话运行态
     */
    public Session createDefault() {
        return create(null, null, null, PermissionMode.NORMAL);
    }

    /**
     * 取当前会话。
     *
     * @return 当前会话，没有当前会话时返回 {@code null}
     */
    public Session current() {
        String sessionId = currentSessionId.get();
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /**
     * 取指定会话，未命中即失败。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 会话运行态
     * @throws JellyfishException 会话标识为空白，或该会话不存在时抛出
     */
    public Session require(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            throw new JellyfishException("sessionId must not be blank");
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new JellyfishException("session not found: " + sessionId);
        }
        return session;
    }

    /**
     * 切换当前会话。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 切到的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session switchTo(String sessionId) {
        Session session = require(sessionId);
        currentSessionId.set(sessionId);
        return session;
    }

    /**
     * 关闭会话：从会话表移除并广播 {@link SessionClosedEvent}；若关闭的正是当前会话，当前指针置空。
     * <p>
     * 幂等：会话不存在（含 {@code sessionId} 为 {@code null}）时返回 {@code null} 且不抛错——关闭路径
     * （进程退出、UI 关窗）不该因为重复关闭而中断收敛。
     * <p>
     * 清当前指针用 {@code compareAndSet}：只有当当前指针仍指向被关闭的会话时才清空，避免把并发切换后
     * 的「新当前会话」误清。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 被关闭的会话运行态，会话不存在时返回 {@code null}
     */
    public Session close(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return null;
        }
        currentSessionId.compareAndSet(sessionId, null);
        publish(new SessionClosedEvent(sessionId, session.getAgentId(), session.size()));
        return session;
    }

    /**
     * 取全部会话。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Collection<Session> all() {
        return Collections.unmodifiableCollection(new ArrayList<Session>(sessions.values()));
    }

    /**
     * 追加一条消息并累加 token 用量，随后广播 {@link SessionMessageAppendedEvent}。
     * <p>
     * 先落会话、再发通知：通知订阅者据 {@code messageId} 回查时，消息一定已经可见。
     * <p>
     * TODO 会话持久化未落地：持久化由插件经 {@code ExtensionRegistry} 同步扩展点完成（架构图
     *      {@code SessionMgr ==> ExtReg}，无返回值但不可丢）。落地时在本方法内、广播通知之后加一次同步
     *      派发，且派发失败必须上抛——那一刻起「消息已追加」与「消息已落盘」必须同生共死。
     *
     * @param sessionId 会话标识，不可为空白
     * @param message   消息本体，不可为 {@code null}
     * @param usage     本次模型调用的 token 用量，可为 {@code null}
     * @return 追加后的会话消息
     * @throws JellyfishException 会话不存在时抛出
     */
    public SessionMessage appendMessage(String sessionId, LlmMessage message, LlmUsage usage) {
        Session session = require(sessionId);
        SessionMessage sessionMessage = SessionMessage.of(message, usage);
        session.append(sessionMessage);
        publish(new SessionMessageAppendedEvent(session.getSessionId(), sessionMessage.getMessageId(),
                sessionMessage.getRole()));
        return sessionMessage;
    }

    /**
     * 设置会话标题。
     *
     * @param sessionId 会话标识，不可为空白
     * @param title     标题，可为 {@code null}
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session updateTitle(String sessionId, String title) {
        Session session = require(sessionId);
        session.setTitle(title);
        return session;
    }

    /**
     * 绑定当前 agentId。
     *
     * @param sessionId 会话标识，不可为空白
     * @param agentId   agentId，可为 {@code null}（表示解绑，权限策略回到 fail-open）
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session bindAgent(String sessionId, String agentId) {
        Session session = require(sessionId);
        session.setAgentId(agentId);
        return session;
    }

    /**
     * 切换当前 provider / model。
     *
     * @param sessionId 会话标识，不可为空白
     * @param provider  provider 名，可为 {@code null}（表示跟随默认）
     * @param model     model 名，可为 {@code null}（表示跟随默认）
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session switchModel(String sessionId, String provider, String model) {
        Session session = require(sessionId);
        session.setModel(provider, model);
        return session;
    }

    /**
     * 切换会话权限模式。
     *
     * @param sessionId      会话标识，不可为空白
     * @param permissionMode 权限模式，{@code null} 按 {@link PermissionMode#NORMAL} 处理
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session setPermissionMode(String sessionId, PermissionMode permissionMode) {
        Session session = require(sessionId);
        session.setPermissionMode(permissionMode);
        return session;
    }

    /**
     * 取会话消息列表的不可修改快照。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 不可修改列表，可能为空但不会为 {@code null}
     * @throws JellyfishException 会话不存在时抛出
     */
    public List<SessionMessage> messagesOf(String sessionId) {
        return require(sessionId).getMessages();
    }

    /**
     * 取会话消息本体的投影，便于调用点直接构建 {@code LlmRequest}。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 不可修改的 {@link LlmMessage} 列表，可能为空但不会为 {@code null}
     * @throws JellyfishException 会话不存在时抛出
     */
    public List<LlmMessage> llmMessagesOf(String sessionId) {
        List<SessionMessage> messages = require(sessionId).getMessages();
        List<LlmMessage> llmMessages = new ArrayList<LlmMessage>(messages.size());
        for (SessionMessage message : messages) {
            llmMessages.add(message.getMessage());
        }
        return Collections.unmodifiableList(llmMessages);
    }

    /**
     * 追加一条会话级待办。
     *
     * @param sessionId 会话标识，不可为空白
     * @param content   待办内容，不可为空白
     * @return 新增的待办项
     * @throws JellyfishException 会话不存在或内容为空白时抛出
     */
    public PendingTodo addTodo(String sessionId, String content) {
        return require(sessionId).addTodo(content);
    }

    /**
     * 按编号标记待办为已完成。
     * <p>
     * 幂等：编号不存在或本就已完成时返回 {@code false}，不抛错——用户重复敲同一条命令不应中断。
     *
     * @param sessionId 会话标识，不可为空白
     * @param todoId    待办编号
     * @return 确实发生状态流转返回 {@code true}
     * @throws JellyfishException 会话不存在时抛出
     */
    public boolean completeTodo(String sessionId, String todoId) {
        return require(sessionId).completeTodo(todoId);
    }

    /**
     * 清空会话全部待办。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 被清空的待办条数
     * @throws JellyfishException 会话不存在时抛出
     */
    public int clearTodos(String sessionId) {
        return require(sessionId).clearTodos();
    }

    /**
     * 取会话待办列表的不可修改快照。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 不可修改列表，可能为空但不会为 {@code null}
     * @throws JellyfishException 会话不存在时抛出
     */
    public List<PendingTodo> todosOf(String sessionId) {
        return require(sessionId).getTodos();
    }

    /**
     * 解析会话创建时应绑定的默认 agentId。
     *
     * @return 默认 agentId，一个 agent 都没配时返回 {@code null}
     */
    private String resolveDefaultAgentId() {
        AgentDefinition definition = agentManager.resolveDefault();
        return definition == null ? null : definition.getAgentId();
    }

    /**
     * 广播会话通知，发布失败只记日志。
     * <p>
     * 会话通知走异步、可丢弃通道：发不出去不应影响会话本身的状态变更，可靠语义由将来的同步落盘扩展点承担。
     *
     * @param event 会话通知事件
     */
    private void publish(JellyfishEvent event) {
        try {
            events.publish(event);
        } catch (RuntimeException e) {
            LOG.warn("session event publish failed: {}", event.getClass().getSimpleName(), e);
        }
    }
}
