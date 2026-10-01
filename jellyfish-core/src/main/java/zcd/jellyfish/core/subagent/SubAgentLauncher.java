package zcd.jellyfish.core.subagent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.RunScope;
import zcd.jellyfish.core.RunScopes;
import zcd.jellyfish.core.prompt.ToolFilter;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.infra.session.SessionUsage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Objects;

/**
 * 子代理委派的机制编排：把「模型请求委派」这件事变成一次真实的嵌套回合。
 * <p>
 * 它本身不做任何智能判断，只按固定顺序把内核已有的能力串起来：
 * <ol>
 *     <li><b>准入</b>：开关是否启用、有没有回合作用域、层数与预算还剩多少、类型是否存在且可委派、
 *     是不是在委派给自己、模型能不能解析、任务是不是空的——<b>这些全部在产生任何副作用之前完成</b>；</li>
 *     <li><b>派生</b>：开一个瞬时会话（不落盘、不进会话列表，但生命周期事件照发）；</li>
 *     <li><b>执行</b>：{@link ReActLooper#runNested} 在<b>调用线程上内联</b>跑完一整个 ReAct 回合；</li>
 *     <li><b>收尾</b>：把子代理花掉的用量归集到父会话，然后关掉子会话。</li>
 * </ol>
 * <b>准入在前是刻意的</b>：一个被拒绝的委派不该留下任何痕迹——不建会话、不发事件、不占预算以外的东西。
 * 唯一的例外是「已通过准入但随后失败」，那时会留下一次已记账的预算与一条已发出的事件；
 * 那正是「它确实尝试过」的如实反映。
 * <p>
 * <b>子代理与主会话相互隔离</b>：它只看得到自己的系统提示词与这次任务原文，看不到父会话的消息、
 * 工具选择与模型——除了 {@code agent_id} 指向的那份 {@code AgentDefinition}（提示词、权限、偏好模型）
 * 与项目约定（{@code AGENTS.md}，由 project 插件的提示词贡献自动注入）。这是刻意的：
 * 委派的价值就来自把噪音留在主对话之外。
 * <p>
 * <b>不变量</b>：本类保证在返回前子会话一定已被关闭；用量归集的失败不会改变委派的结果
 * （账目不准不该升级成任务失败）。
 *
 * @author zcd
 */
@Singleton
public class SubAgentLauncher {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentLauncher.class);

    /** 会话域服务：开瞬时会话、归集用量、收尾。 */
    private final SessionManager sessionManager;

    /** agent 门面：校验委派目标并取它的定义。 */
    private final AgentManager agentManager;

    /** 会话模型解析器：委派前先确认模型解析得出来。 */
    private final SessionModelResolver sessionModelResolver;

    /** 运行时配置门面：读 {@code subAgent} 段。 */
    private final RuntimeConfig runtimeConfig;

    /** ReAct 循环器：嵌套回合的执行体。 */
    private final ReActLooper reActLooper;

    /** 委派作用域持有者：层数与预算的账本。 */
    private final RunScopes runScopes;

    /** 权限管理器：用它给出子代理这一轮能看到哪些工具。 */
    private final PermissionManager permissionManager;

    /**
     * 构造子代理委派器。
     *
     * @param sessionManager      会话域服务，不可为 {@code null}
     * @param agentManager        agent 门面，不可为 {@code null}
     * @param sessionModelResolver 会话模型解析器，不可为 {@code null}
     * @param runtimeConfig       运行时配置门面，不可为 {@code null}
     * @param reActLooper         ReAct 循环器，不可为 {@code null}
     * @param runScopes           委派作用域持有者，不可为 {@code null}
     * @param permissionManager   权限管理器，不可为 {@code null}
     */
    @Inject
    public SubAgentLauncher(SessionManager sessionManager, AgentManager agentManager,
                            SessionModelResolver sessionModelResolver, RuntimeConfig runtimeConfig,
                            ReActLooper reActLooper, RunScopes runScopes, PermissionManager permissionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.sessionModelResolver = Objects.requireNonNull(sessionModelResolver,
                "sessionModelResolver must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.reActLooper = Objects.requireNonNull(reActLooper, "reActLooper must not be null");
        this.runScopes = Objects.requireNonNull(runScopes, "runScopes must not be null");
        this.permissionManager = Objects.requireNonNull(permissionManager,
                "permissionManager must not be null");
    }

    /**
     * 执行一次委派：同步跑完子代理的整个回合并返回结果。
     * <p>
     * <b>同步且内联</b>：调用方是工具执行线程，它本来就要等这次委派结束才能把结果回灌给模型。
     *
     * @param call     委派请求，不可为 {@code null}
     * @param listener 子代理回合的流式回调（用于把进度转给外壳），可为 {@code null}
     * @return 委派结果，保证非 {@code null}
     */
    public SubAgentOutcome run(SubAgentCall call, ReActListener listener) {
        Objects.requireNonNull(call, "call must not be null");
        SubAgentSettings settings = runtimeConfig.getSubAgentSettings();
        if (!settings.isEnabled()) {
            return SubAgentOutcome.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）");
        }
        if (call.hasBlankPrompt()) {
            return SubAgentOutcome.rejected("任务描述不能为空：子代理看不到本次对话，它只有这段描述");
        }
        RunScope scope = runScopes.current();
        if (scope == null) {
            return SubAgentOutcome.rejected("当前没有进行中的回合，无法委派子代理");
        }
        if (!scope.canDelegate()) {
            return SubAgentOutcome.rejected(limitReason(scope));
        }
        AgentDefinition definition = agentManager.find(call.getAgentId());
        if (definition == null) {
            return SubAgentOutcome.rejected("未知的子代理类型：" + call.getAgentId() + delegatableHint());
        }
        if (!definition.isDelegatable()) {
            return SubAgentOutcome.rejected("agent [" + call.getAgentId()
                    + "] 未声明 delegatable，不能作为委派目标");
        }
        try {
            Session parent = sessionManager.require(call.getParentSessionId());
            if (call.getAgentId().equals(parent.getAgentId())) {
                return SubAgentOutcome.rejected("不能把任务委派给当前 agent 自己：" + call.getAgentId());
            }
            // 模型先行：配置写错时连子会话都不该建，更不该发出一轮注定失败的模型调用
            sessionModelResolver.resolveByAgentOrDefault(call.getAgentId());
            scope.recordSpawn();
            return delegate(call, listener, parent, settings);
        } catch (RuntimeException e) {
            LOG.warn("子代理委派失败: parent={} agent={}", call.getParentSessionId(), call.getAgentId(), e);
            return SubAgentOutcome.failed(messageOf(e));
        }
    }

    /**
     * 派生并执行子代理回合，返回前保证子会话已关闭。
     *
     * @param call     委派请求
     * @param listener 流式回调，可为 {@code null}
     * @param parent   父会话运行态
     * @param settings 本次生效的子代理设置
     * @return 委派结果，保证非 {@code null}
     */
    private SubAgentOutcome delegate(SubAgentCall call, ReActListener listener, Session parent,
                                     SubAgentSettings settings) {
        Session child = null;
        try {
            // 权限模式继承父会话：子代理不该比派它的那个会话更宽松。
            // 模型与 provider 留空——它们由子代理自己的 agent 定义决定，不继承父会话。
            child = sessionManager.createEphemeral(parent.getSessionId(), call.getAgentId(),
                    null, null, parent.getPermissionMode());
            ReActResult result = reActLooper.runNested(child, call.getPrompt(), listener,
                    call.getCancellationToken(), settings.getMaxRounds(), toolFilterOf(call, parent));
            return toOutcome(result, child);
        } catch (RuntimeException e) {
            LOG.warn("子代理回合失败: parent={} agent={}", call.getParentSessionId(), call.getAgentId(), e);
            return SubAgentOutcome.failed(messageOf(e));
        } finally {
            forwardUsage(call.getParentSessionId(), child);
            closeQuietly(child);
        }
    }

    /**
     * 组装子代理这一轮能看到哪些工具的过滤。
     * <p>
     * 判据取自 {@code PermissionManager}，与执行期判定同一份规则：子代理只该看到自己那份 agent 配置
     * 允许的工具，不该被邀请去调用一个会被拒的东西。权限模式继承父会话（只在 PLAN 下额外收窄到只读），
     * 但<a>主会话的工具清单不受影响</a>——过滤只随嵌套回合传递。
     *
     * @param call   委派请求
     * @param parent 父会话运行态
     * @return 过滤器，保证非 {@code null}
     */
    private ToolFilter toolFilterOf(SubAgentCall call, Session parent) {
        return ToolFilter.of(permissionManager.usableTools(call.getAgentId(), parent.getPermissionMode()));
    }

    /**
     * 把子代理回合的终态翻译成委派结果。
     *
     * @param result 子代理回合结果
     * @param child  子会话运行态
     * @return 委派结果，保证非 {@code null}
     */
    private static SubAgentOutcome toOutcome(ReActResult result, Session child) {
        if (result.isCancelled()) {
            return SubAgentOutcome.cancelled(result.getRounds(), child.getUsage());
        }
        if (result.isTruncated()) {
            return SubAgentOutcome.truncated(truncatedText(result, child), result.getRounds(), child.getUsage());
        }
        return SubAgentOutcome.completed(result.getContent(), result.getRounds(), child.getUsage());
    }

    /**
     * 组装「达到轮数上限」时的回灌文本：内核提示 + 子代理最后一段已产出的正文。
     * <p>
     * <b>为什么要补上正文</b>：被截断意味着子代理还没写出结论，只回一句通知会让主会话对它做过什么
     * 一无所知——而它已经把好几轮花在翻查上了，那些过程本身就是此刻唯一可用的线索。
     * <p>
     * <b>为什么只给最后一段而不是全部轮次</b>：截断时每一轮的正文都只是过程、没有结论，
     * 全量回灌等于用主会话的上下文预算替子代理的寒暄买单；而回灌文本一旦超限，会被工具输出限流
     * 按「头 30% / 尾 70%」截断，占住开头那 30% 的恰恰是信息量最低的早期内容。取最后一段既是
     * 「它最后在想什么」的最近似答案，篇幅也天然可控（通常远低于 {@code react.maxToolOutputChars}）。
     * <p>
     * <b>为什么明说只附了一段</b>：不说的话，主会话会把这段过程文本当成子代理的全部交代。
     *
     * @param result 子代理回合结果
     * @param child  子会话运行态
     * @return 回灌文本，保证非 {@code null}
     */
    private static String truncatedText(ReActResult result, Session child) {
        String hint = result.getContent() == null ? "" : result.getContent().trim();
        String text = lastAssistantText(child);
        if (StringUtils.isBlank(text)) {
            // 一句正文都没写：只留内核提示，不为「空内容」另编一句说明
            return hint;
        }
        return hint + "\n（以下是它最后一段已产出的正文，更早的轮次未一并回灌）\n" + text;
    }

    /**
     * 取子会话里最后一条带正文的助手消息。
     * <p>
     * <b>为什么倒着找而不是直接取最后一条消息</b>：被截断时最后一轮必然停在工具调用上，
     * 那条助手消息可能只带工具调用、没有正文，因此要往前找到第一条真有文本的。
     * <p>
     * <b>为什么不带用户消息与工具结果</b>：要的是子代理自己的话，不是它看到的任务原文或工具回显。
     *
     * @param child 子会话运行态
     * @return 正文文本；一句都没写过时返回 {@code null}
     */
    private static String lastAssistantText(Session child) {
        List<SessionMessage> messages = child.getMessages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmMessage message = messages.get(index).getMessage();
            if (message == null || !LlmMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                continue;
            }
            if (StringUtils.isNotBlank(message.getContent())) {
                return message.getContent().trim();
            }
        }
        return null;
    }

    /**
     * 把子代理花掉的用量归集到父会话。
     * <p>
     * <b>为什么在这里而不是让子会话自己带着走</b>：子会话马上就要被关掉，它的用量随之下落不明；
     * 而那些 token 是真花掉的，该算在这个对话头上。
     * <p>
     * <b>失败只记日志</b>：本方法跑在 {@code finally} 里，任何异常都会顶掉已经跑出来的委派结果。
     * 账目不准是小事，把一次成功的委派变成失败是大事。
     *
     * @param parentSessionId 父会话标识
     * @param child           子会话运行态，可为 {@code null}（从未创建时）
     */
    private void forwardUsage(String parentSessionId, Session child) {
        if (child == null) {
            return;
        }
        SessionUsage usage = child.getUsage();
        if (usage.getLlmCalls() <= 0) {
            return;
        }
        try {
            sessionManager.recordUsage(parentSessionId, usage);
        } catch (RuntimeException e) {
            LOG.warn("子代理用量归集失败: parent={}", parentSessionId, e);
        }
    }

    /**
     * 关闭子会话，失败只记日志（理由同 {@link #forwardUsage}）。
     *
     * @param child 子会话运行态，可为 {@code null}（从未创建时）
     */
    private void closeQuietly(Session child) {
        if (child == null) {
            return;
        }
        try {
            sessionManager.close(child.getSessionId());
        } catch (RuntimeException e) {
            LOG.warn("子代理会话收尾失败: sessionId={}", child.getSessionId(), e);
        }
    }

    /**
     * 组装「已达上限」的拒绝理由。
     * <p>
     * 分开说深度与预算：两者对模型的指导完全不同——深度用尽意味着「这件事得自己做」，
     * 预算用尽意味着「这一轮派得太多了」。
     *
     * @param scope 当前作用域
     * @return 理由文本
     */
    private static String limitReason(RunScope scope) {
        if (scope.getDepth() >= scope.getMaxDepth()) {
            return "已达委派层数上限（" + scope.getMaxDepth() + " 层），请自己完成这件事";
        }
        return "本次回合已派出 " + scope.getSpawnCount() + " 个子代理，达到上限 "
                + scope.getMaxSpawnsPerTurn() + "，请自己完成剩余工作";
    }

    /**
     * 组装可用子代理清单，附在「未知类型」的错误后面。
     * <p>
     * 模型拿不到 {@code agents.json}，一个「未知的类型」如果不说有哪些可用，它只能瞎猜。
     *
     * @return 提示文本，保证非 {@code null}
     */
    private String delegatableHint() {
        StringBuilder names = new StringBuilder();
        for (AgentDefinition definition : agentManager.all()) {
            if (definition == null || !definition.isDelegatable()) {
                continue;
            }
            if (names.length() > 0) {
                names.append('、');
            }
            names.append(definition.getAgentId());
        }
        return names.length() == 0
                ? "（当前没有任何 agent 声明 delegatable）"
                : "；可用：" + names;
    }

    /**
     * 取异常的可用消息。
     *
     * @param throwable 异常
     * @return 消息文本；消息为空时退化为类名
     */
    private static String messageOf(Throwable throwable) {
        return StringUtils.isBlank(throwable.getMessage())
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
