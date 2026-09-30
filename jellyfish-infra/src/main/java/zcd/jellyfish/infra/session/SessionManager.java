package zcd.jellyfish.infra.session;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.CompactionAppliedEvent;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.LlmCallFailedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.SessionMessageAppendedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.LifecycleVerdict;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.api.extension.SessionBeforeForkRequest;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.extension.SessionKind;
import zcd.jellyfish.api.extension.SessionPersistRequest;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.llm.LlmHttpException;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <b>唯一变更入口</b>：所有会话运行态变更（追加消息 / 改标题 / 绑 agent / 切模型 / 切权限模式 /
 * 应用压缩 / 记一次不产生消息的用量）
 * 都必须经本类。因为这些变更要连带做两件横切的事——广播通知、同步落盘扩展点
 * （架构图 {@code SessionMgr ==> ExtReg}）——集中在一处才不会每个调用点各写一遍。
 * <p>
 * <b>持久化是一等职责而非旁路</b>：状态变更会同步派发 {@link SessionPersistRequest}，处理器抛出的异常
 * <b>原样上抛</b>——那一刻起「状态已变」与「状态已落盘」必须同生共死。落盘的是整个会话快照，
 * 因此上一次失败的状态会在下一次任何变更时被一并补上。启动期则由 {@link #restore()} 反向向插件要回会话。
 * <p>
 * <b>两个例外，都是刻意的</b>：
 * <ol>
 *     <li><b>创建不落盘</b>：{@link #create} 只把会话放进内存。空会话（建了却一个字没说）不该在磁盘上
 *     留下文件与一条 git 提交，用户「进来看一眼」与「真的用起来」应当能区分开。第一次真实变更
 *     （含追加消息）会把它落下来——<b>会话文件 = 用户真的对它做过事的会话</b>。</li>
 *     <li><b>回合内的消息追加不逐条落盘</b>：一次 ReAct 回合会产生 2N+2 条消息（N 为轮数），逐条落盘
 *     等于把整个会话快照重写 2N+2 次，还附带同数量的 git 提交。因此
 *     {@link #beginTurn(String)} 到 {@link #flush(String)} 之间只标脏，回合终结时落一次。
 *     崩潰时最多丢「正在进行的那一个回合」，而<b>已经收敛的回合一定已经落盘</b>。</li>
 * </ol>
 * <p>
 * <b>子代理会话是另一类不落盘的会话</b>：由 {@link #createEphemeral} 创建，靠「父会话标识非空」
 * 识别。它们同样是会话表里的真实会话（能追加消息、发事件、被回查），只是不进 {@link #all()}、
 * 不落盘；收尾走 {@link #close(String)}，与普通会话同一条路径——「事件照发」是刻意的：
 * 把子代理从指标与界面里藏起来，恰恰让人看不见最需要被看见的那一段。
 * <p>
 * <b>延迟落盘的失败语义与即时落盘相反</b>：即时落盘失败上抛（回合随之中止），而 {@link #flush(String)}
 * 失败只记 WARN 并<b>保留脏标记</b>等下一次重试——那时回合已经收敛、回答已经展示给用户，
 * 把它升级成「回合失败」既补不回来也无从补救，{@link #flushAll()} 在关停时还有一次机会。
 * <p>
 * <b>不持有全局模型状态</b>：当前模型是会话字段，本类只做读写转发；解析与路由仍归 {@code ModelManager}，
 * 因此同一进程内的不同会话可以各用各的模型。唯一的例外是"新建会话时的默认值"
 * （{@link SessionDefaults}，由首页上的 {@code /model} 等命令写入）——它只在 {@link #create} 那一刻
 * 被当成缺省值填进新会话，填完就跟会话再无关系，之后的切换依然只改会话字段。
 * <p>
 * <b>本轮不做</b>：上下文裁剪与 token 预算——归 {@code core/prompt}，本类只做计量。待办这类
 * 领域状态不再由会话持有，改由插件自持（经提示词贡献扩展点注入），因此本类也不提供对应入口。
 *
 * @author zcd
 */
@Singleton
public class SessionManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** 单条扩展条目的值上限（字节，按内核的规范编码量）。 */
    public static final int EXTENSION_VALUE_MAX_BYTES = 64 * 1024;

    /** 每会话扩展条目数上限。 */
    public static final int EXTENSION_ENTRY_MAX_COUNT = 64;

    /** 扩展条目 key 长度上限（字符）。 */
    public static final int EXTENSION_KEY_MAX_CHARS = 256;

    /** agent 门面，仅用于会话创建时解析默认 agent。 */
    private final AgentManager agentManager;

    /** 通知发布入口，用于广播会话生命周期通知。 */
    private final EventPublisher events;

    /** 同步扩展点策略，会话持久化与恢复的唯一通道。 */
    private final ExtensionRegistry extensions;

    /** 会话表：sessionId → 会话运行态。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    /** 本进程内「新建会话时使用的默认值」，由首页上的 {@code /model} {@code /agent} {@code /mode} 写入。 */
    private final SessionDefaults sessionDefaults;

    /**
     * 正在回合内、因此暂缓逐条落盘的会话。
     * <p>
     * 由 {@link #beginTurn(String)} 加入、{@link #flush(String)} 移出；回合终结走 {@code finally}，
     * 因此异常与取消路径也不会把它留在里面。
     */
    private final Set<String> deferred = ConcurrentHashMap.newKeySet();

    /**
     * 有未落盘变更的会话。
     * <p>
     * 只服务于延迟落盘那一条路径：即时落盘的变更当场就写完了，不进这个集合。
     */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    /** 当前会话标识；{@code null} 表示当前没有会话。 */
    private final AtomicReference<String> currentSessionId = new AtomicReference<String>();

    /**
     * 构造会话域服务。
     *
     * @param agentManager    agent 门面，用于解析会话创建时的默认 agent
     * @param events          通知发布入口
     * @param extensions      同步扩展点策略，用于派发持久化与恢复请求
     * @param sessionDefaults 本进程内新建会话的默认值，用于填补调用方未指定的那几项
     */
    @Inject
    public SessionManager(AgentManager agentManager, EventPublisher events, ExtensionRegistry extensions,
                          SessionDefaults sessionDefaults) {
        this.agentManager = agentManager;
        this.events = events;
        this.extensions = extensions;
        this.sessionDefaults = sessionDefaults;
    }

    /**
     * 创建一个会话并返回其运行态。
     * <p>
     * {@code agentId} 为空白时按 {@link AgentManager#resolveDefault()} 绑定默认 agent，结果<b>可能仍为
     * {@code null}</b>——「一个 agent 都没配」是合法状态（全员 fail-open），不应让会话创建失败。
     * <p>
     * <b>未指定的那几项先落到「本进程的待生效默认值」上</b>（{@link SessionDefaults}）：它由首页上的
     * {@code /model} {@code /agent} {@code /mode} 写入，表达的是「我接下来这次对话要用它」。
     * 那里也没设过才回到最下层——agent 走内置默认，provider / model 留 {@code null}。
     * <p>
     * <b>为什么 provider / model 仍然可以留 {@code null}</b>：它们只影响路由，{@code null} 表示
     * 「跟随默认」，由调用点在真正发起 LLM 调用时用 {@code ModelManager.resolveDefault()} 解析——
     * 那个方法在没有模型时抛异常，若在这里调用会把「没配模型」拖成会话创建失败。
     * <p>
     * <b>本方法不自动把新会话设为当前会话</b>：并发创建不应互相抢占当前指针，切换由调用点显式
     * {@link #switchTo(String)} 完成。
     * <p>
     * <b>本方法不落盘</b>：建了就落会为「进来看一眼」留下一个空会话文件（以及一条「0 条消息」的 git
     * 提交）。落盘改由第一次真实变更触发，因此一个从未被使用过的会话在进程退出后自然消失。
     * 代价是「创建失败」不再在创建那一刻暴露——但磁盘不可写这类问题会在第一次变更时当场暴露，
     * 而且比「创建时失败」晚不了多少。
     *
     * @param agentId        agent 标识，可为空白（按默认 agent 绑定）
     * @param provider       provider 名，可为 {@code null}（按待生效默认值、其次跟随默认）
     * @param model          model 名，可为 {@code null}（按待生效默认值、其次跟随默认）
     * @param permissionMode 权限模式，可为 {@code null}（按待生效默认值、其次 NORMAL）
     * @return 新建的会话运行态
     */
    public Session create(String agentId, String provider, String model, PermissionMode permissionMode) {
        SessionDefaults.Values defaults = sessionDefaults.snapshot();
        String requestedAgentId = agentId == null ? defaults.getAgentId() : agentId;
        String boundAgentId = StringUtils.isBlank(requestedAgentId) ? resolveDefaultAgentId() : requestedAgentId;
        Session session = new Session(UUID.randomUUID().toString(), boundAgentId,
                provider == null ? defaults.getProvider() : provider,
                model == null ? defaults.getModel() : model,
                permissionMode == null ? defaults.getPermissionMode() : permissionMode,
                System.currentTimeMillis());
        // 刻意不落盘：空会话不留文件，第一次真实变更时再落（见方法注释）
        sessions.put(session.getSessionId(), session);
        publish(new SessionCreatedEvent(boundAgentId, session.getSessionId()));
        return session;
    }

    /**
     * 按待生效默认值创建一个会话。
     * <p>
     * <b>四项全部传 {@code null}</b>（而不是显式传 {@code NORMAL}）：{@code null} 表示
     * 「按本进程的待生效默认值，其次按更下层的默认」，这正是首页上 {@code /mode plan} 能生效的前提。
     * 传 {@code NORMAL} 会把那一层默认值直接跳过。
     *
     * @return 新建的会话运行态
     */
    public Session createDefault() {
        return create(null, null, null, null);
    }

    /**
     * 创建一个子代理会话：与 {@link #create} 的差别只有两处，都写在方法名里。
     * <p>
     * <b>不读待生效默认值</b>：{@link SessionDefaults} 表达的是「我接下来这次对话要用它」，
     * 而子代理的身份（agentId）、模型与权限模式完全由委派方给定，跟首页上那个选择无关；
     * 传 {@code null} 的项直接落到更下层默认（模型走全局默认，权限走 NORMAL）。
     * <p>
     * <b>会话仍是真实会话</b>：进会话表、能追加消息、生命周期事件照发（携带父会话标识）。
     * 它不进 {@link #all()}、不落盘：前者因为「子代理不是用户可切换的会话」，
     * 后者因为「一次委派的对话不值得留下一份文件与一条 git 提交」。
     * <p>
     * <b>谁负责收尾</b>：调用方必须在 {@code finally} 里调 {@link #close(String)}——
     * 与普通会话不同，子代理会话没有「用户下次回来接着聊」这回事，留着只会泄内存。
     *
     * @param parentSessionId 派生该会话的父会话标识，不可为空白
     * @param agentId         agent 标识，可为空白（按默认 agent 绑定）
     * @param provider        provider 名，可为 {@code null}（跟随全局默认）
     * @param model           model 名，可为 {@code null}（跟随全局默认）
     * @param permissionMode  权限模式，可为 {@code null}（按 {@link PermissionMode#NORMAL} 处理）
     * @return 新建的子代理会话运行态
     * @throws JellyfishException 父会话标识为空白时抛出
     */
    public Session createEphemeral(String parentSessionId, String agentId, String provider, String model,
                                   PermissionMode permissionMode) {
        if (StringUtils.isBlank(parentSessionId)) {
            throw new JellyfishException("parentSessionId must not be blank");
        }
        String boundAgentId = StringUtils.isBlank(agentId) ? resolveDefaultAgentId() : agentId;
        Session session = new Session(UUID.randomUUID().toString(), boundAgentId, provider, model,
                permissionMode, System.currentTimeMillis(), SessionKind.EPHEMERAL, parentSessionId, null);
        sessions.put(session.getSessionId(), session);
        publish(new SessionCreatedEvent(boundAgentId, session.getSessionId(), parentSessionId));
        return session;
    }

    /**
     * 从已有会话的某一点分出一条新会话。
     * <p>
     * <b>v1 只做单向 fork，不做完整会话树</b>：完整树要动会话的核心表示、TUI 的导航交互、
     * 压缩边界语义与配对规则，风险与收益不成比例；而「从某一步复制出一条新会话」已经能覆盖
     * 绝大多数真实需求（检查点、A/B 对比、改坏了回退重来）。
     * <p>
     * <b>切点必须落在工具调用组的边界上</b>：{@code messageId} 指到一条工具结果时，切点会沿连续的
     * 工具结果<b>向后推</b>到这一组的末尾。两个方向都能避开「半组工具」，但只有向后能保证
     * <b>复制范围一定包含被指定的那条消息</b>；向前退会静默丢掉它，而且落点是一条没有结果的
     * {@code assistant(tool_use)}——内核在组装请求时本来就会把那种结尾当成无效消息丢掉
     * （见 {@code PromptAssembler.dropTrailingDanglingToolCalls}），于是那条消息「在历史里在、
     * 模型永远看不到」。
     * <p>
     * <b>为什么不复制用量</b>：那是源会话花掉的钱。fork 之后要花新钱，带过去会把成本重复计入。
     * <p>
     * <b>压缩摘要按「边界是否落在复制范围内」取舍</b>：在范围内就原样带过去（它仍然代表被丢出
     * 上下文的那一段），不在范围内就丢弃（那一刻本来还没压过）。压缩是非破坏式的（消息一条不删），
     * 因此两种情况都不丢数据，两种都精确复原了源会话在那一刻的状态。
     * <p>
     * <b>扩展条目照带</b>：它们是「会话在那一刻的状态」的一部分（插件写的检查点、已扫过的文件……），
     * 丢了它们，「回退到某一步」会得到一个自身标记全无的会话。
     * <p>
     * <b>新会话的种类是 {@link SessionKind#FORKED}</b>：它与普通会话同等对待（进会话列表、
     * 落盘、可被 {@code /resume} 与 {@code /delete}），而不是子代理的临时工作区。
     * 因此它<b>当场落盘</b>：fork 是一个显式动作，不属于「创建不落盘」那个例外。
     *
     * @param sessionId 源会话标识，不可为空白
     * @param messageId 分支点消息标识，可为 {@code null}（表示从末尾分支）
     * @param title     新会话标题，可为 {@code null}（继承源会话标题）
     * @return 新建的分支会话运行态
     * @throws JellyfishException 源会话不存在、消息标识不存在、空会话，或插件拦下了本次 fork 时抛出
     */
    public Session fork(String sessionId, String messageId, String title) {
        Session source = require(sessionId);
        int cutIndex = resolveCutIndex(source, messageId);
        if (cutIndex < 0) {
            throw new JellyfishException("没有可复制的历史，无法分支: sessionId=" + sessionId);
        }
        // 否决钩子排在复制之前：拦下了就什么都不复制、不落盘，因此不存在「一半的新会话」
        LifecycleVerdict verdict = beforeFork(source, messageId, cutIndex);
        if (verdict.isCancelled()) {
            throw new JellyfishException("会话分支被插件拦下：" + reasonOf(verdict.getReason()));
        }
        List<SessionMessage> copied = source.getMessages().subList(0, cutIndex + 1);
        Session forked = new Session(UUID.randomUUID().toString(), source.getAgentId(), source.getProvider(),
                source.getModel(), source.getPermissionMode(), System.currentTimeMillis(),
                SessionKind.FORKED, sessionId, copied.get(cutIndex).getMessageId());
        forked.setTitle(title == null ? forkTitleOf(source) : title);
        // 复制消息但<b>不累加它们的 token 用量</b>：那是源会话花掉的钱，
        // fork 之后要花新钱，带过去会把成本重复计入
        forked.copyMessagesWithoutUsage(copied);
        forked.setCompaction(copiedCompaction(source, cutIndex));
        // 扩展条目也要带：它们是「会话在那一刻的状态」的一部分（插件写的检查点、已扫过的文件……），
        // 而 fork 的语义就是精确复原那一刻。丢了它们，「回退到某一步」会得到一个自身标记全无的会话
        for (SessionExtensionEntry entry : source.getExtensionEntries()) {
            forked.putExtensionEntry(entry);
        }
        sessions.put(forked.getSessionId(), forked);
        publish(new SessionCreatedEvent(forked.getAgentId(), forked.getSessionId(), sessionId));
        persist(forked);
        LOG.info("已分支会话: source={} forked={} messages={}", sessionId, forked.getSessionId(), forked.size());
        return forked;
    }

    /**
     * 解析并对齐分支切点。
     * <p>
     * 对齐规则：指定的那条是工具结果时，沿连续的同类消息向后推到这一组的末尾。
     * 向前退会得到一条没有结果的 {@code assistant(tool_use)}，而内核在组装请求时会把那种结尾丢掉。
     *
     * @param source    源会话
     * @param messageId 分支点消息标识，可为 {@code null}
     * @return 切点下标（含）；空会话或消息不存在时返回 {@code -1}
     * @throws JellyfishException 消息标识存在但找不到时抛出
     */
    private int resolveCutIndex(Session source, String messageId) {
        List<SessionMessage> messages = source.getMessages();
        if (messages.isEmpty()) {
            return -1;
        }
        if (messageId == null) {
            return messages.size() - 1;
        }
        int index = source.indexOfMessage(messageId);
        if (index < 0) {
            throw new JellyfishException("分支点消息不存在: messageId=" + messageId);
        }
        while (index + 1 < messages.size() && isToolResult(messages.get(index + 1))) {
            index++;
        }
        return index;
    }

    /**
     * 判断一条消息是不是工具结果。
     * <p>
     * <b>为什么没有直接用 core 的 {@code ToolPairing}</b>：本类在 infra，而 core 依赖 infra，
     * 反过来不成立。判定本身只有一个事实（角色是 {@code tool}），而「为什么要对齐」的完整说明在
     * {@code ToolPairing} 那里；「往哪边对齐」在 {@link #resolveCutIndex} 上。
     *
     * @param message 会话消息，不可为 {@code null}
     * @return 工具结果返回 {@code true}
     */
    private static boolean isToolResult(SessionMessage message) {
        return LlmMessage.ROLE_TOOL.equals(message.getMessage().getRole());
    }

    /**
     * 复制源会话的压缩摘要。
     * <p>
     * <b>判定必须在切点对齐之后</b>：对齐能把切点往后推，而边界与切点的先后关系决定了该不该带。
     *
     * @param source   源会话
     * @param cutIndex 对齐后的切点下标（含）
     * @return 应当带过去的摘要；不该带时为 {@code null}
     */
    private static SessionCompaction copiedCompaction(Session source, int cutIndex) {
        SessionCompaction compaction = source.getCompaction();
        if (compaction == null) {
            return null;
        }
        int boundary = source.indexOfMessage(compaction.getBoundaryMessageId());
        if (boundary < 0 || boundary > cutIndex) {
            // 边界不在复制范围内：那一刻本来还没压过，不带才是准确复原
            return null;
        }
        return compaction;
    }

    /**
     * 取分支会话的缺省标题：源会话标题加一个后缀，没标题时用源会话标识。
     *
     * @param source 源会话
     * @return 标题，保证非空白
     */
    private static String forkTitleOf(Session source) {
        String base = StringUtils.isBlank(source.getTitle()) ? source.getSessionId() : source.getTitle();
        return base + " (分支)";
    }

    /**
     * 跑一遗会话分支前钩子链，返回最终裁定。
     * <p>
     * <b>链式语义与关闭前钩子同口径</b>（写在调用点的 {@code for} 循环里，注册表不参与）：
     * 第一个 {@code cancel} 短路，没有任何处理器时直接放行。
     *
     * @param source    源会话
     * @param messageId 调用方原始指定的分支点，可为 {@code null}
     * @param cutIndex  对齐后的切点下标（含）
     * @return 裁定结果，保证非 {@code null}
     */
    private LifecycleVerdict beforeFork(Session source, String messageId, int cutIndex) {
        List<HandlerBinding<SessionBeforeForkRequest, LifecycleVerdict>> bindings =
                extensions.bindings(SessionBeforeForkRequest.class, null);
        if (bindings.isEmpty()) {
            return LifecycleVerdict.proceed();
        }
        SessionBeforeForkRequest request = new SessionBeforeForkRequest(source.getSessionId(),
                source.getAgentId(), messageId, cutIndex, cutIndex + 1);
        for (HandlerBinding<SessionBeforeForkRequest, LifecycleVerdict> binding : bindings) {
            LifecycleVerdict verdict;
            try {
                verdict = extensions.invoke(binding.getHandler(), request);
            } catch (RuntimeException e) {
                // 处理器抛错按「放行」处理：与其余生命周期钩子同口径，一个坏插件不该让用户分不出分支
                LOG.warn("会话分支前钩子抛错，按放行处理: owner={} sessionId={}",
                        binding.getOwner(), source.getSessionId(), e);
                continue;
            }
            if (verdict != null && verdict.isCancelled()) {
                return verdict;
            }
        }
        return LifecycleVerdict.proceed();
    }

    /**
     * 写入（或替换）一条会话扩展条目。
     * <p>
     * <b>它走既有的「唯一变更入口 + 标脏 + flush 落盘」机制</b>，不新增第三条落盘路径：
     * 回合内只标脏，回合终结时与消息一起落一次。
     * <p>
     * <b>key 的 owner 前缀由调用方给全</b>：插件侧入口（{@code PluginContext}）把
     * {@code pluginId} 或 {@code pluginId::子标识} 拼在前面，本方法不猜。这样「谁在写」在
     * 上限报错与诊断输出里都是明确的，也不会因为两个调用方各拼一次而分叉。
     * <p>
     * <b>上限是硬的，超限拒写而不截断</b>：截断一个映射会留下「看起来完整、实际缺字段」
     * 的数据；静默淘汰则会让插件「写成功、重启后没了」。两者都比当场报错难排查得多。
     *
     * @param sessionId 会话标识，不可为空白
     * @param owner     写入方的 owner 命名空间，不可为空白，仅用于报错归因
     * @param key       完整 key（含 owner 前缀），不可为空白
     * @param value     值，可为 {@code null}（等价空映射）
     * @return 写入后的会话运行态
     * @throws JellyfishException 会话不存在、会话标识 / owner / key 为空白，或超限时抛出
     */
    public Session putExtensionEntry(String sessionId, String owner, String key, Map<String, Object> value) {
        Session session = require(sessionId);
        if (StringUtils.isBlank(owner)) {
            throw new JellyfishException("extension entry owner must not be blank");
        }
        if (StringUtils.isBlank(key)) {
            throw new JellyfishException("extension entry key must not be blank: owner=" + owner);
        }
        if (key.length() > EXTENSION_KEY_MAX_CHARS) {
            throw new JellyfishException("扩展条目的 key 过长（上限 " + EXTENSION_KEY_MAX_CHARS
                    + " 字符）: owner=" + owner + " keyLength=" + key.length());
        }
        SessionExtensionEntry existing = session.extensionEntry(key);
        if (existing == null && session.getExtensionEntries().size() >= EXTENSION_ENTRY_MAX_COUNT) {
            throw new JellyfishException("会话的扩展条目已达上限（" + EXTENSION_ENTRY_MAX_COUNT
                    + " 条），请先删旧条目: owner=" + owner + " sessionId=" + sessionId);
        }
        SessionExtensionEntry entry = new SessionExtensionEntry(key, value, System.currentTimeMillis());
        int bytes = extensionEntryBytes(entry);
        if (bytes > EXTENSION_VALUE_MAX_BYTES) {
            throw new JellyfishException("扩展条目的值过大（上限 " + EXTENSION_VALUE_MAX_BYTES
                    + " 字节）: owner=" + owner + " key=" + key + " bytes=" + bytes);
        }
        session.putExtensionEntry(entry);
        markDirtyOrPersist(session);
        return session;
    }

    /**
     * 删除一条会话扩展条目。
     * <p>
     * 删不存在的 key 不算失败：调用的意图「这条不在」已经达成了。
     *
     * @param sessionId 会话标识，不可为空白
     * @param key       完整 key（含 owner 前缀），不可为空白
     * @return 写入后的会话运行态
     * @throws JellyfishException 会话不存在或会话标识 / key 为空白时抛出
     */
    public Session removeExtensionEntry(String sessionId, String key) {
        Session session = require(sessionId);
        if (StringUtils.isBlank(key)) {
            throw new JellyfishException("extension entry key must not be blank");
        }
        if (session.removeExtensionEntry(key)) {
            markDirtyOrPersist(session);
        }
        return session;
    }

    /**
     * 取某会话的全部扩展条目（含所有 owner）。
     *
     * @param sessionId 会话标识，不可为空白
     * @return 不可修改列表，可能为空但不会为 {@code null}
     * @throws JellyfishException 会话不存在时抛出
     */
    public List<SessionExtensionEntry> extensionEntries(String sessionId) {
        return require(sessionId).getExtensionEntries();
    }

    /**
     * 取某会话在某个 owner 命名空间下的扩展条目：{@code owner} 自身与 {@code owner::*} 一并命中。
     * <p>
     * 前缀匹配必须带分隔符：否则 {@code plugin-a} 会把 {@code plugin-ab} 的条目一起看到。
     *
     * @param sessionId 会话标识，不可为空白
     * @param owner     owner 命名空间根，不可为空白
     * @return 不可修改列表，可能为空但不会为 {@code null}
     * @throws JellyfishException 会话不存在或 owner 为空白时抛出
     */
    public List<SessionExtensionEntry> extensionEntriesOf(String sessionId, String owner) {
        if (StringUtils.isBlank(owner)) {
            throw new JellyfishException("extension entry owner must not be blank");
        }
        String prefix = owner + PluginOwnerNamespace.SEPARATOR;
        List<SessionExtensionEntry> visible = new ArrayList<SessionExtensionEntry>();
        for (SessionExtensionEntry entry : require(sessionId).getExtensionEntries()) {
            if (entry.getKey().startsWith(prefix)) {
                visible.add(entry);
            }
        }
        return Collections.unmodifiableList(visible);
    }

    /**
     * 量一条条目的规范编码字节数。
     * <p>
     * <b>为何是「内核的规范编码」而不是「序列化后」</b>：文件格式由持久化插件决定，内核无从得知。
     * 上限的目的是「别让一个坏插件撑爆会话文件」，因此一个确定、可测、与真实编码同量级的度量就够了；
     * 写死成某个插件的格式反而会让本类依赖它。
     *
     * @param entry 条目，不可为 {@code null}
     * @return 字节数
     */
    private static int extensionEntryBytes(SessionExtensionEntry entry) {
        return ObjectMapperWrapper.writeValueAsBytes(entry.getValue()).length;
    }

    /**
     * 按延迟落盘的现有纪律落一步：回合内只标脏，否则当场落。
     *
     * @param session 刚被改过的会话
     */
    private void markDirtyOrPersist(Session session) {
        if (deferred.contains(session.getSessionId())) {
            dirty.add(session.getSessionId());
        } else {
            persist(session);
        }
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
        // 无原因的重载归为「内部收尾」：它今天唯一的调用点是瞬时子代理会话跑完，
        // 那既不是用户意图也不是进程收尾，与 SHELL 关心的三类原因不是一回事
        return close(sessionId, SessionBeforeCloseRequest.Reason.INTERNAL);
    }

    /**
     * 关闭会话，并声明这次关闭是谁想要的。
     * <p>
     * <b>为什么要把原因显式传进来</b>：它决定插件能不能拦。只有 {@link Reason#USER_REQUEST}
     * 的否决会被采纳——进程收尾、配置重载与内部收尾都是一个必须完成的事实，
     * 在那里按插件的意愿留下一个「本该关掉的会话」只会变成资源泄漏。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param reason    关闭原因，不可为 {@code null}
     * @return 被关闭的会话运行态，会话不存在或关闭被拦下时返回 {@code null}
     * @throws JellyfishException 关闭被插件拦下（仅 {@code USER_REQUEST}）或落盘失败时抛出
     */
    public Session close(String sessionId, SessionBeforeCloseRequest.Reason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        // 关闭前钩子排在落盘之前：插件在这里做收尾（写检查点、导出记录）或拦下这一次关闭。
        // 排在落盘之后就没有「拦下」可言了，而收尾也只会晚于持久化、看到一份已经写出去的快照
        LifecycleVerdict verdict = beforeClose(session, reason);
        if (verdict.isCancelled() && reason == SessionBeforeCloseRequest.Reason.USER_REQUEST) {
            throw new JellyfishException("会话关闭被插件拦下：" + reasonOf(verdict.getReason()));
        }
        // 先落最后一次快照再移除：落盘失败时宁可不关，也不要留下「已关闭但没存下」的会话
        persist(session);
        sessions.remove(sessionId);
        deferred.remove(sessionId);
        dirty.remove(sessionId);
        currentSessionId.compareAndSet(sessionId, null);
        publish(new SessionClosedEvent(sessionId, session.getAgentId(), session.size(),
                session.getParentSessionId()));
        return session;
    }

    /**
     * 跑一遍会话关闭前钩子链，返回最终裁定。
     * <p>
     * <b>链式语义</b>（写在调用点的 {@code for} 循环里，注册表不参与）：第一个 {@code cancel} 短路，
     * 理由取自它。
     * <p>
     * <b>失败语义是「放行」</b>：同步派发没有护栏，异常处置是本方法的责任；插件坏掉不该把
     * 关不掉的会话留在进程里。
     * <p>
     * <b>无插件时不构造任何请求对象</b>。
     *
     * @param session 会话运行态，不可为 {@code null}
     * @param reason  关闭原因，不可为 {@code null}
     * @return 最终裁定；链上没有可用处理器或都没意见时为 {@link LifecycleVerdict#proceed()}
     */
    private LifecycleVerdict beforeClose(Session session, SessionBeforeCloseRequest.Reason reason) {
        List<ExtensionHandler<SessionBeforeCloseRequest, LifecycleVerdict>> handlers =
                extensions.handlers(SessionBeforeCloseRequest.class, null);
        if (handlers.isEmpty()) {
            return LifecycleVerdict.proceed();
        }
        String sessionId = session.getSessionId();
        String agentId = session.getAgentId();
        for (ExtensionHandler<SessionBeforeCloseRequest, LifecycleVerdict> handler : handlers) {
            LifecycleVerdict verdict;
            try {
                verdict = extensions.invoke(handler, new SessionBeforeCloseRequest(sessionId, agentId, reason));
            } catch (RuntimeException e) {
                LOG.warn("会话关闭前处理器抛错，按放行处理: sessionId={}", sessionId, e);
                continue;
            }
            if (verdict != null && verdict.isCancelled()) {
                return verdict;
            }
        }
        return LifecycleVerdict.proceed();
    }

    /**
     * 取插件给出的理由的可用文本。
     *
     * @param reason 理由，可为 {@code null}
     * @return 理由文本，空时返回固定占位
     */
    private static String reasonOf(String reason) {
        return StringUtils.isBlank(reason) ? "未提供理由" : reason;
    }

    /**
     * 删除会话：先把插件那一份存储删掉，再从会话表移除并广播关闭事件。
     * <p>
     * <b>与 {@link #close(String)} 的分工</b>：{@code close} 只是结束运行态、把最后的快照落盘保留；
     * 本方法是「这个会话不要了」，磁盘上的那一份也要一起清掉，因此它不会先落快照。
     * <p>
     * <b>为什么先派发删除再移出会话表</b>：删除是破坏性操作，失败时不能留下不一致状态。
     * 处理器抛出的异常原样上抛（同步侧无护栏），此时会话仍留在内存里、用户看到的是「删除失败」；
     * 反过来先移除再派发，插件失败就会变成「界面说删了、文件还在、下次启动复活」这种三处对不上的状态。
     * <p>
     * <b>幂等</b>：会话不存在（含 {@code sessionId} 为 {@code null}）时返回 {@code null} 且不抛错，
     * 也不派发删除请求——删除一个不存在的会话不需要任何清理动作。
     * <p>
     * 清当前指针用 {@code compareAndSet}：只有当前指针仍指向被删除的会话时才清空，
     * 避免把并发切换后的「新当前会话」误清。外壳据此回到无会话状态（TUI 首页）。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 被删除的会话运行态，会话不存在时返回 {@code null}
     * @throws JellyfishException 插件删除失败时抛出（此时会话保留在内存中）
     */
    public Session delete(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        deletePersisted(session);
        sessions.remove(sessionId);
        deferred.remove(sessionId);
        dirty.remove(sessionId);
        currentSessionId.compareAndSet(sessionId, null);
        publish(new SessionClosedEvent(sessionId, session.getAgentId(), session.size(),
                session.getParentSessionId()));
        return session;
    }

    /**
     * 启动期恢复会话：向所有注册了恢复处理器的插件要回会话快照并导入。
     * <p>
     * 必须在插件启动<b>之后</b>调用——插件要先注册处理器，才可能被问到。
     * <p>
     * <b>失败语义与落盘相反</b>：单个插件读不出自己的备份只记告警并跳过，不阻断启动。
     * 理由是不对称的——落盘失败会丢新数据，而恢复失败只是回到「从零开始」，后者不该让进程起不来。
     * <p>
     * 恢复的会话同样广播 {@code SessionCreatedEvent}：对订阅者而言它确实是刚出现在本进程里的会话。
     *
     * @return 实际导入的会话数
     */
    public int restore() {
        int imported = 0;
        for (HandlerBinding<SessionRestoreRequest, SessionRestoreResult> binding
                : extensions.bindings(SessionRestoreRequest.class, null)) {
            SessionRestoreResult result;
            try {
                result = extensions.invoke(binding.getHandler(), new SessionRestoreRequest());
            } catch (RuntimeException e) {
                LOG.warn("会话恢复失败，跳过该来源: owner={}", binding.getOwner(), e);
                continue;
            }
            imported += importSnapshots(result);
        }
        if (imported > 0) {
            LOG.info("已从插件恢复会话: count={}", imported);
        }
        return imported;
    }

    /**
     * 取全部会话。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    /**
     * 取全部「用户可切换的」会话。
     * <p>
     * <b>子代理会话不在其中</b>：它们同样在会话表里（否则消息追加、事件广播、回查都做不了），
     * 但它们是某次委派的中间产物，不属于「我的会话列表」：列出来既选不中（它随时会被关掉），
     * 又让人分不清哪个是自己在用的。需要观察子代理的订阅者走事件（带父会话标识）。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Collection<Session> all() {
        List<Session> visible = new ArrayList<Session>(sessions.size());
        for (Session session : sessions.values()) {
            if (!session.isEphemeral()) {
                visible.add(session);
            }
        }
        return Collections.unmodifiableCollection(visible);
    }

    /**
     * 追加一条不带元数据的消息并累加 token 用量。
     *
     * @param sessionId 会话标识，不可为空白
     * @param message   消息本体，不可为 {@code null}
     * @param usage     本次模型调用的 token 用量，可为 {@code null}
     * @param thinking  本次模型调用的思考过程，可为 {@code null}
     * @return 追加后的会话消息
     * @throws JellyfishException 会话不存在时抛出
     */
    public SessionMessage appendMessage(String sessionId, LlmMessage message, LlmUsage usage, String thinking) {
        return appendMessage(sessionId, message, usage, thinking, null);
    }

    /**
     * 追加一条消息并累加 token 用量，随后落盘或标脏，最后广播 {@link SessionMessageAppendedEvent}。
     * <p>
     * 先落会话、再落盘、最后发通知：通知订阅者据 {@code messageId} 回查时，消息一定已经可见；
     * 落盘必须在通知之前，否则「已落盘的会话」会少掉订阅者已经看到的那条消息。
     * <p>
     * <b>落盘与否取决于当前是否在回合内</b>：在
     * {@link #beginTurn(String)} 与 {@link #flush(String)} 之间只标脏（回合结束落一次），
     * 其余情形即时落盘。即时落盘失败时异常上抛（同步侧无护栏，处置是调用点的责任）：
     * 调用方应当让本次回合失败。两种情况都已入内存的这条消息都不会被回滚——落盘写的是整个会话快照，
     * 下一次成功落盘会把它一并补上。
     * <p>
     * <b>为什么要收一个 {@code metadata} 形参而不是另开一个「工具消息」入口</b>：消息的落盘与通知
     * 顺序是这里唯一的复杂度，复制一份给工具消息就等于把这段顺序维护两遍。元数据本身只对工具结果
     * 有意义，因此非工具路径一律传 {@code null}。
     *
     * @param sessionId 会话标识，不可为空白
     * @param message   消息本体，不可为 {@code null}
     * @param usage     本次模型调用的 token 用量，可为 {@code null}
     * @param thinking  本次模型调用的思考过程，可为 {@code null}
     * @param metadata  工具结果的结构化元数据，可为 {@code null}（等价空映射）
     * @return 追加后的会话消息
     * @throws JellyfishException 会话不存在时抛出
     */
    public SessionMessage appendMessage(String sessionId, LlmMessage message, LlmUsage usage, String thinking,
                                        Map<String, Object> metadata) {
        Session session = require(sessionId);
        SessionMessage sessionMessage = new SessionMessage(UUID.randomUUID().toString(),
                System.currentTimeMillis(), message, usage, thinking, metadata);
        session.append(sessionMessage);
        if (deferred.contains(sessionId)) {
            // 回合内：只标脏，由回合终结时的 flush 统一落一次
            dirty.add(sessionId);
        } else {
            persist(session);
        }
        publish(new SessionMessageAppendedEvent(session.getSessionId(), sessionMessage.getMessageId(),
                sessionMessage.getRole()));
        publishUsage(session, usage);
        return sessionMessage;
    }

    /**
     * 广播「一次模型调用已记账」。
     * <p>
     * <b>为什么在记账处发而不是在调用处发</b>：用量进会话有两条路径（追加一条带用量的消息、
     * 只记用量而不留消息），在调用处发就得把两条都记住；放在唯一的记账漏斗上，两条自动都覆盖。
     * <p>
     * <b>用量为 {@code null} 时不发</b>：「厂商没返回用量」不是一次可计量的调用，
     * 发出去只会让订阅方多一堆要过滤的零。
     * <p>
     * <b>合并子代理累计量的那条路径不发</b>：那份用量在子代理的会话里已经逐次发过了，
     * 再发一次会让「这个进程一共几次模型调用」把子代理的账重复计入。
     *
     * @param session 会话运行态
     * @param usage   一次调用的用量，可为 {@code null}
     */
    private void publishUsage(Session session, LlmUsage usage) {
        if (usage == null) {
            return;
        }
        publish(new LlmCallCompletedEvent(session.getSessionId(), session.getProvider(), session.getModel(),
                new TokenUsageSnapshot(usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), usage.getCacheReadTokens(), usage.getCacheWriteTokens())));
    }

    /**
     * 把一次<b>失败的</b>模型调用广播出去。
     * <p>
     * <b>它不写会话状态</b>：失败的调用没有用量可言，会话里也没有任何东西可计。它存在的唯一理由是把
     * 「这一次被拒了」放到可订阅的通道上——{@link LlmCallCompletedEvent} 只覆盖成功的调用，
     * 于是插件无法得知自己前一次下的缓存字段被端点拒了，也就无法自我修正。
     * <p>
     * <b>与用量入口同属一处而不是散在三个调用点</b>：回合、压缩、缓存保活三条路径都会失败，
     * 而它们的报告口径必须一致（同一个事件类型、同一套状态码提取），放在这里才不会三处各写一遍。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param model     模型标识，可为 {@code null}
     * @param error     失败原因，不可为 {@code null}
     */
    public void publishCallFailure(String sessionId, String model, JellyfishException error) {
        Objects.requireNonNull(error, "error must not be null");
        // 取不到就当没有：失败的调用可能发生在会话已被关掉之后，而「报一条事件」不该因此抛异常
        Session session = sessionId == null ? null : sessions.get(sessionId);
        publish(new LlmCallFailedEvent(sessionId,
                session == null ? null : session.getProvider(),
                model == null && session != null ? session.getModel() : model,
                statusCodeOf(error), error.getMessage()));
    }

    /**
     * 从异常里取出 HTTP 状态码。
     * <p>
     * <b>取不到就是 {@code 0}</b>，而不是抛或猜：网络异常、超时、反序列化失败都不是 HTTP 层面的失败，
     * 对订阅方而言「不是 HTTP 失败」本身就是一条可用信息（它排除了「字段被拒」）。
     *
     * @param error 失败原因
     * @return HTTP 状态码；非 HTTP 失败时返回 {@code 0}
     */
    private static int statusCodeOf(JellyfishException error) {
        return error instanceof LlmHttpException ? ((LlmHttpException) error).getStatusCode() : 0;
    }

    /**
     * 开始一个回合：该会话在回合内的消息追加只标脏，不逐条落盘。
     * <p>
     * 幂等：重复调用只是再往集合里放一次。回合终结必须配对调 {@link #flush(String)}，
     * 否则这次回合的消息要到进程关停时才会被 {@link #flushAll()} 补上。
     * <p>
     * <b>只有消息追加被挂起</b>：命令、（{@code /compact} 的）压缩与用量、关闭等入口仍即时落盘。
     * 因此跑在独立线程上的自动压缩不受回合作用域影响——它本来就是一个独立的落盘单元。
     *
     * @param sessionId 会话标识，可为 {@code null}（忽略）
     */
    public void beginTurn(String sessionId) {
        if (sessionId != null) {
            deferred.add(sessionId);
        }
    }

    /**
     * 结束一个回合并落盘：把该会话移出延迟态，有未落盘变更就落一次。
     * <p>
     * <b>失败只记 WARN，不上抛，也不清脏标记</b>：调用点是回合线程的 {@code finally}，
     * 此刻回答已经生成并展示给用户，把落盘失败升级成回合失败无法补救；保留脏标记则让
     * {@link #flushAll()} 在关停时还有一次机会。
     *
     * @param sessionId 会话标识，可为 {@code null}（忽略）
     */
    public void flush(String sessionId) {
        if (sessionId == null) {
            return;
        }
        deferred.remove(sessionId);
        if (!dirty.contains(sessionId)) {
            return;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            // 会话在回合中途被删了：脏标记无处可落，不该留着让关停时再找一遍
            dirty.remove(sessionId);
            return;
        }
        flushQuietly(session);
    }

    /**
     * 落盘所有未落盘的会话，供进程关停时调用。
     * <p>
     * <b>为什么关停必须有这一步</b>：回合级落盘把「每次变更即时落盘」换成了「回合终结落一次」，
     * 于是正常退出这条路径也必须显式补一次，否则用户按 Ctrl+C 就会丢掉当前回合。
     * <p>
     * <b>调用时机是硬约束</b>：必须在插件停止<b>之前</b>——落盘经 {@code ExtensionRegistry} 派发
     * {@link SessionPersistRequest}，插件没了就没人落盘了。
     * <p>
     * 失败同样只记 WARN：关停路径上的任何一步都不应该阻断后面的收尾。
     */
    public void flushAll() {
        for (String sessionId : new ArrayList<String>(dirty)) {
            Session session = sessions.get(sessionId);
            if (session == null) {
                dirty.remove(sessionId);
                continue;
            }
            flushQuietly(session);
        }
        if (!dirty.isEmpty()) {
            LOG.warn("仍有会话未能落盘: count={}", dirty.size());
        }
    }

    /**
     * 追加一条不带思考过程的消息并累加 token 用量，随后同步落盘并广播事件。
     *
     * @param sessionId 会话标识，不可为空白
     * @param message   消息本体，不可为 {@code null}
     * @param usage     本次模型调用的 token 用量，可为 {@code null}
     * @return 追加后的会话消息
     * @throws JellyfishException 会话不存在时抛出
     */
    public SessionMessage appendMessage(String sessionId, LlmMessage message, LlmUsage usage) {
        return appendMessage(sessionId, message, usage, null);
    }

    /**
     * 应用一次压缩结果：记下摘要与新的边界，同步落盘并广播 {@link CompactionAppliedEvent}。
     * <p>
     * <b>消息一条不删</b>：压缩是非破坏式的，本方法只改「请求从哪里开始」这一笔账。屏幕投影、
     * 持久化与 {@code /resume} 拿到的会话都还是完整历史，只有发给模型的那条链路会按边界截断
     * （见 {@code core/prompt/PromptAssembler}）。
     * <p>
     * <b>为什么要校验边界比旧边界更靠后</b>：边界只能向后移。允许它回退，就意味着「已经不在请求里的
     * 那一段」会重新出现在请求里，而摘要仍然覆盖着它——同一段内容被讲两遍，且新旧摘要互相矛盾。
     * 调用点在派发之前就拦住这种情况（那属于逻辑错误，不该走到落盘）。
     *
     * @param sessionId         会话标识，不可为空白
     * @param summary           摘要正文，不可为空白
     * @param boundaryMessageId 摘要覆盖到的最后一条消息标识，不可为空白
     * @param droppedMessageCount 本次因超出摘要输入预算而被直接丢弃的条数，负数按 0 处理
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在、参数为空白，或边界消息不在会话里时抛出
     */
    public Session applyCompaction(String sessionId, String summary, String boundaryMessageId,
                                   int droppedMessageCount) {
        Session session = require(sessionId);
        int boundaryIndex = session.indexOfMessage(boundaryMessageId);
        if (boundaryIndex < 0) {
            throw new JellyfishException("compaction boundary message not found: " + boundaryMessageId);
        }
        int compressedCount = boundaryIndex + 1 - Math.max(0, droppedMessageCount);
        session.setCompaction(new SessionCompaction(summary, boundaryMessageId, System.currentTimeMillis(),
                droppedMessageCount));
        persist(session);
        publish(new CompactionAppliedEvent(sessionId, boundaryMessageId, compressedCount,
                Math.max(0, droppedMessageCount), summary.length()));
        return session;
    }

    /**
     * 把一次不产生会话消息的模型调用计入会话用量，并同步落盘。
     * <p>
     * <b>为什么需要它</b>：{@code /compact} 的摘要调用花的是真实的 token，但它不该在对话里留下一条
     * 消息（屏幕投影会多出一条谁也没说过的话）。用量是「会话花掉了多少」这一笔账，与消息列表无关，
     * 因此给它一个独立入口。**广播 {@link LlmCallCompletedEvent}**：这次调用不产生会话消息，
     * 如果连事件也不发，它在进程级就彻底不可见（{@code /usage} 读的是会话状态，进程退出即消失）。
     *
     * @param sessionId 会话标识，不可为空白
     * @param usage     一次调用的用量，可为 {@code null}（只累加调用次数）
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session recordUsage(String sessionId, LlmUsage usage) {
        Session session = require(sessionId);
        session.recordUsage(usage);
        persist(session);
        publishUsage(session, usage);
        return session;
    }

    /**
     * 把一次<b>嵌套回合</b>的累计用量并入会话，并同步落盘。
     * <p>
     * <b>为什么不让调用方把总量包成一个 {@link LlmUsage} 再走上面那个入口</b>：那个入口恒定只
     * 加一次调用，而子代理的一个回合可能调了很多次模型。会话的「调用次数」是判断
     * 「这一轮到底花了多少来回」的依据，把它压成 1 会让统计失真。
     *
     * @param sessionId 会话标识，不可为空白
     * @param usage     嵌套回合的累计用量，可为 {@code null}（按无变化处理）
     * @return 变更后的会话运行态
     * @throws JellyfishException 会话不存在时抛出
     */
    public Session recordUsage(String sessionId, SessionUsage usage) {
        Session session = require(sessionId);
        session.recordUsage(usage);
        persist(session);
        return session;
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
        persist(session);
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
        persist(session);
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
        persist(session);
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
        persist(session);
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
     * 解析会话创建时应绑定的默认 agentId。
     *
     * @return 默认 agentId，一个 agent 都没配时返回 {@code null}
     */
    private String resolveDefaultAgentId() {
        AgentDefinition definition = agentManager.resolveDefault();
        return definition == null ? null : definition.getAgentId();
    }

    /**
     * 同步派发会话删除：无返回值，但不可丢。
     * <p>
     * 与持久化同样的取舍：同一份存储可能同时落在文件、数据库与远端，这些是「都做」而不是「二选一」。
     * 没有插件注册时删除只发生在内存里——没有持久化插件是合法状态。
     * <p>
     * <b>与落盘共用同一把会话级锁</b>：否则一个正在途中的落盘会在删完之后把文件又写回来（复活）。
     * 处理器抛出的异常原样上抛，由 {@link #delete(String)} 的调用点决定处置。
     *
     * @param session 待删除的会话运行态
     */
    private void deletePersisted(Session session) {
        List<HandlerBinding<SessionDeleteRequest, Void>> bindings =
                extensions.bindings(SessionDeleteRequest.class, null);
        if (bindings.isEmpty()) {
            return;
        }
        SessionDeleteRequest request = new SessionDeleteRequest(session.getSessionId());
        session.underPersistLock(() -> {
            for (HandlerBinding<SessionDeleteRequest, Void> binding : bindings) {
                extensions.invoke(binding.getHandler(), request);
            }
        });
    }

    /**
     * 同步派发会话持久化并清掉脏标记：失败原样上抛，由调用点决定处置。
     * <p>
     * 走类型级贡献而不是具名处理器：同一份会话可以同时落文件、写数据库、推给远端，这些是「都做」。
     * <p>
     * 没有插件注册时直接返回，不做任何快照构造——没有持久化插件是合法状态，不该自担开销。
     * 此时脏标记照清：脏的含义是「有变更还没交给持久化扩展点」，没有扩展点就等于没人可交。
     *
     * @param session 待落盘的会话运行态
     * @throws JellyfishException 任一处理器抛出时原样上抛
     */
    private void persist(Session session) {
        doPersist(session);
        dirty.remove(session.getSessionId());
    }

    /**
     * 落盘但不让失败传播：失败只记 WARN 并<b>保留脏标记</b>，等下一次 {@link #flush(String)} 或
     * {@link #flushAll()} 重试。
     * <p>
     * 只在延迟落盘那一条路径上用（见类注释的失败语义说明）。
     *
     * @param session 待落盘的会话运行态
     */
    private void flushQuietly(Session session) {
        try {
            doPersist(session);
            dirty.remove(session.getSessionId());
        } catch (RuntimeException e) {
            LOG.warn("会话落盘失败，保留未落盘标记待下次重试: sessionId={}", session.getSessionId(), e);
        }
    }

    /**
     * 把快照派发给全部持久化处理器；不碰脏标记，失败原样上抛。
     * <p>
     * <b>捕获与派发在同一把会话级锁里</b>：每一次落盘写入的是「捕获那一刻的整个会话」，
     * 因此两次并发落盘若各自先捕获再写，完全可能是「较旧的快照后写」——磁盘上最后留下的是旧的那份。
     * 锁住之后，落盘顺序就等于状态推进顺序，最后写下的必定是最新的快照。
     * <p>
     * <b>锁是按会话分的，不是全局的</b>：不同会话的落盘互不阻塞（插件写文件、跑 git 都可能耗时）。
     * 拿不到处理器时提前返回，也避免为一次空派发去抢锁。
     *
     * @param session 待落盘的会话运行态
     */
    private void doPersist(Session session) {
        // 子代理会话不落盘：它是一次委派的中间产物，没有「下次回来接着用」这回事；
        // 写入只会给会话目录留下一批谁也认领不了的文件（会话已从表里移除，_path 也无从对应）
        if (session.isEphemeral()) {
            return;
        }
        List<HandlerBinding<SessionPersistRequest, Void>> bindings =
                extensions.bindings(SessionPersistRequest.class, null);
        if (bindings.isEmpty()) {
            return;
        }
        session.underPersistLock(() -> {
            SessionPersistRequest request = new SessionPersistRequest(SessionSnapshots.capture(session));
            for (HandlerBinding<SessionPersistRequest, Void> binding : bindings) {
                extensions.invoke(binding.getHandler(), request);
            }
        });
    }

    /**
     * 导入一批会话快照。
     * <p>
     * 已存在的会话标识一律保留内存里那一份：恢复只负责「把不认识的会话带回来」，
     * 而不是覆盖运行期已经开始的会话。
     *
     * @param result 恢复结果，可为 {@code null}
     * @return 实际导入的会话数
     */
    private int importSnapshots(SessionRestoreResult result) {
        if (result == null) {
            return 0;
        }
        int imported = 0;
        for (SessionSnapshot snapshot : result.getSessions()) {
            Session session = Session.restore(snapshot);
            if (sessions.putIfAbsent(session.getSessionId(), session) != null) {
                LOG.warn("会话已存在，跳过恢复: sessionId={}", session.getSessionId());
                continue;
            }
            publish(new SessionCreatedEvent(session.getAgentId(), session.getSessionId()));
            imported++;
        }
        return imported;
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
