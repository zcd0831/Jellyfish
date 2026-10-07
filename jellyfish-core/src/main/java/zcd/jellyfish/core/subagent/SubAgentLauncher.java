package zcd.jellyfish.core.subagent;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActLooper;
import zcd.jellyfish.core.ReActResult;
import zcd.jellyfish.core.prompt.ToolFilter;
import zcd.jellyfish.core.runtime.AgentRunHandle;
import zcd.jellyfish.core.runtime.AgentRunRequest;
import zcd.jellyfish.core.runtime.AgentRunResult;
import zcd.jellyfish.core.runtime.AgentRunStatus;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.core.runtime.RunContext;
import zcd.jellyfish.core.runtime.RunContextHolder;
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
 *     <li><b>执行</b>：把 {@link ReActLooper#runNested} 交给 {@link AgentRuntime#spawn} 在
 *     {@code agent-run} 线程上跑，然后 {@link AgentRuntime#await} 等它——委派本身仍是同步的
 *     （调用方本来就要等结果才能回灌给模型），但等待已经不占用父回合的执行线程；</li>
 *     <li><b>收尾</b>：把子代理花掉的用量归集到父会话，归档这次 run 的完整过程，然后关掉子会话。</li>
 * </ol>
 * <b>派生与等待是两个可分开使用的入口</b>：{@link #spawn(SubAgentCall, ReActListener)} 立即返回句柄，
 * {@link #await(SubAgentRunHandle)} 在调用方线程上等终态并收尾；{@link #run(SubAgentCall, ReActListener)}
 * 是两者的简写（{@code task} 工具用的就是它）。拆开是为并发扇出：先连发 N 个 {@code spawn}，
 * 再逐个 {@code await}，而两者共用同一套准入与收尾。
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

    /** 未收敛且两条来源都没给出原因时的兜底说明：宁可说「不知道」，也不要编一个原因。 */
    private static final String UNKNOWN_TRUNCATION_REASON = "子代理未收敛（内核未给出原因）";

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
    private final RunContextHolder runContexts;

    /** 权限管理器：用它给出子代理这一轮能看到哪些工具。 */
    private final PermissionManager permissionManager;

    /** agent run 门面：登记与终结本次委派的 run（身份与生命周期，不含执行调度）。 */
    private final AgentRuntime runtime;

    /** run 归档器：把子会话的完整过程留在磁盘上。 */
    private final SubAgentArchive archive;

    /**
     * 构造子代理委派器。
     *
     * @param sessionManager      会话域服务，不可为 {@code null}
     * @param agentManager        agent 门面，不可为 {@code null}
     * @param sessionModelResolver 会话模型解析器，不可为 {@code null}
     * @param runtimeConfig       运行时配置门面，不可为 {@code null}
     * @param reActLooper         ReAct 循环器，不可为 {@code null}
     * @param runContexts           委派作用域持有者，不可为 {@code null}
     * @param permissionManager   权限管理器，不可为 {@code null}
     * @param runtime             agent run 门面，不可为 {@code null}
     * @param archive             run 归档器，不可为 {@code null}
     */
    @Inject
    public SubAgentLauncher(SessionManager sessionManager, AgentManager agentManager,
                            SessionModelResolver sessionModelResolver, RuntimeConfig runtimeConfig,
                            ReActLooper reActLooper, RunContextHolder runContexts, PermissionManager permissionManager,
                            AgentRuntime runtime, SubAgentArchive archive) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.sessionModelResolver = Objects.requireNonNull(sessionModelResolver,
                "sessionModelResolver must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.reActLooper = Objects.requireNonNull(reActLooper, "reActLooper must not be null");
        this.runContexts = Objects.requireNonNull(runContexts, "runContexts must not be null");
        this.permissionManager = Objects.requireNonNull(permissionManager,
                "permissionManager must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.archive = Objects.requireNonNull(archive, "archive must not be null");
    }

    /**
     * 执行一次委派：等待并返回子代理的最终结论。
     * <p>
     * <b>阻塞在调用方线程上</b>：调用方是工具执行线程，它本来就要等这次委派结束才能把结果回灌给模型。
     * 等待期间父回合并不占 run 槽位（见 {@code RunScheduler}）。
     * <p>
     * 这是 {@code await(spawn(...))} 的简写：两个入口共用同一套准入与收尾。
     *
     * @param call     委派请求，不可为 {@code null}
     * @param listener 子代理回合的流式回调（用于把进度转给外壳），可为 {@code null}
     * @return 委派结果，保证非 {@code null}
     */
    public SubAgentOutcome run(SubAgentCall call, ReActListener listener) {
        return await(spawn(call, listener));
    }

    /**
     * 派生一次委派：准入 → 建子会话 → 交给调度器。立即返回，不阻塞。
     * <p>
     * <b>准入全部在产生副作用之前完成</b>，被拒的委派不建会话、不发事件、不占预算之外的东西。
     * 无论被拒还是派生途中失败，返回的句柄都直接带着终态结果（{@link SubAgentRunHandle#isSettled()}），
     * 因此调用方只需要 {@code spawn → await} 两步，不必为「早失败」另写一条分支。
     * <p>
     * <b>为什么要它能不阻塞地返回</b>：并发扇出靠它——先连发 N 个 {@code spawn}，再逐个等待。
     * 若派生自己阻塞，扇出会退化成串行，而串行正是编排最不该有的性质。
     *
     * @param call     委派请求，不可为 {@code null}
     * @param listener 子代理回合的流式回调，可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    public SubAgentRunHandle spawn(SubAgentCall call, ReActListener listener) {
        Objects.requireNonNull(call, "call must not be null");
        SubAgentSettings settings = runtimeConfig.getSubAgentSettings();
        if (!settings.isEnabled()) {
            return SubAgentRunHandle.settled(
                    SubAgentOutcome.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）"));
        }
        if (call.hasBlankPrompt()) {
            return SubAgentRunHandle.settled(
                    SubAgentOutcome.rejected("任务描述不能为空：子代理看不到本次对话，它只有这段描述"));
        }
        RunContext scope = runContexts.current();
        if (scope == null) {
            return SubAgentRunHandle.settled(
                    SubAgentOutcome.rejected("当前没有进行中的回合，无法委派子代理"));
        }
        if (!scope.canDelegate()) {
            return SubAgentRunHandle.settled(SubAgentOutcome.rejected(limitReason(scope)));
        }
        AgentDefinition definition = agentManager.find(call.getAgentId());
        if (definition == null) {
            return SubAgentRunHandle.settled(SubAgentOutcome.rejected(
                    "未知的子代理类型：" + call.getAgentId() + delegatableHint()));
        }
        if (!definition.isDelegatable()) {
            return SubAgentRunHandle.settled(SubAgentOutcome.rejected(
                    "agent [" + call.getAgentId() + "] 未声明 delegatable，不能作为委派目标"));
        }
        try {
            Session parent = sessionManager.require(call.getParentSessionId());
            if (call.getAgentId().equals(parent.getAgentId())) {
                return SubAgentRunHandle.settled(
                        SubAgentOutcome.rejected("不能把任务委派给当前 agent 自己：" + call.getAgentId()));
            }
            // 模型先行：配置写错时连子会话都不该建，更不该发出一轮注定失败的模型调用
            sessionModelResolver.resolveByAgentOrDefault(call.getAgentId());
            // 原子地「判定并占用」派生名额：上面那次 canDelegate 只是尽早拒绝，
            // 真正占名额必须一次 CAS 完成，否则并行派生时会集体超发
            if (!scope.tryAcquireSpawn()) {
                return SubAgentRunHandle.settled(SubAgentOutcome.rejected(limitReason(scope)));
            }
            return derive(call, listener, parent, settings);
        } catch (RuntimeException e) {
            LOG.warn("子代理委派失败: parent={} agent={}", call.getParentSessionId(), call.getAgentId(), e);
            return SubAgentRunHandle.settled(SubAgentOutcome.failed(messageOf(e)));
        }
    }

    /**
     * 等终态并收尾：归集用量 → 归档 → 关子会话 → 摘登记表。
     * <p>
     * <b>阻塞在调用方线程上</b>，这是本方法存在的意义之一：等待既不占 {@code react} 池，
     * 也不需要内核为插件维护一套完成回调（内核绝不在关键路径上同步回调插件）。
     * <p>
     * <b>幂等</b>：收尾只发生一次，结果缓存在句柄上，重复等待返回同一个结果。
     * <b>早失败也走这里</b>：句柄可能已经建了子会话甚至登记了 run，因此收尾必须与正常路径同一个出口，
     * 否则「派生失败」会漏掉子会话与登记表的清理。
     *
     * @param handle 句柄，不可为 {@code null}
     * @return 委派结果，保证非 {@code null}
     */
    public SubAgentOutcome await(SubAgentRunHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        SubAgentOutcome cached = handle.getOutcome();
        if (cached != null) {
            return cached;
        }
        Session child = handle.getChild();
        SubAgentOutcome outcome;
        try {
            outcome = handle.getSettled() != null
                    ? handle.getSettled()
                    : toOutcome(runtime.await(handle.getRunHandle()), child);
        } catch (RuntimeException e) {
            LOG.warn("子代理回合失败: parent={} runId={}", handle.getParentSessionId(), handle.getRunId(), e);
            outcome = SubAgentOutcome.failed(messageOf(e));
        } finally {
            forwardUsage(handle.getParentSessionId(), child);
            archiveQuietly(handle.getRunId(), child);
            closeQuietly(child);
            // 终态条目不留着：run 的身份与终态已经写进归档，留在内存里只会随会话运行时间线性增长。
            // 顺序不能反——归档要读快照，移除之后就再也拿不到了
            if (handle.getRunId() != null) {
                runtime.remove(handle.getRunId());
            }
        }
        handle.settle(outcome);
        return outcome;
    }

    /**
     * 建子会话并派生 run：把 {@link SubAgentCall} 变成运行时里的一次 run。
     * <p>
     * 只做派生，不做等待——等待与收尾统一在 {@link #await(SubAgentRunHandle)}，
     * 两条调用路径（{@code task} 与面向插件的委派端口）因此共用同一个出口。
     *
     * @param call     委派请求
     * @param listener 流式回调，可为 {@code null}
     * @param parent   父会话运行态
     * @param settings 本次生效的子代理设置
     * @return 句柄，保证非 {@code null}
     */
    private SubAgentRunHandle derive(SubAgentCall call, ReActListener listener, Session parent,
                                     SubAgentSettings settings) {
        Session child = null;
        String runId = null;
        try {
            // 模型与 provider 留空——它们由子代理自己的 agent 定义决定，不继承父会话。
            child = sessionManager.createEphemeral(parent.getSessionId(), call.getAgentId(),
                    null, null);
            // 执行体是「在 agent-run 线程上跑一次嵌套回合」：句柄同时是取消令牌，取消与超时都能掐断它的 LLM 流
            final Session childSession = child;
            AgentRunRequest request = new AgentRunRequest(parent.getSessionId(), call.getAgentId(),
                    childSession.getSessionId(), null);
            AgentRunHandle handle = runtime.spawn(request, call.getCancellationToken(), runHandle -> {
                ReActResult result = reActLooper.runNested(childSession, call.getPrompt(), listener, runHandle,
                        settings.getMaxRounds(), toolFilterOf(call, parent));
                return AgentRunResult.of(runStatusOf(result), result.getContent(), result.getRounds(),
                        childSession.getUsage(), null);
            });
            runId = handle.getRunId();
            return SubAgentRunHandle.of(call.getParentSessionId(), childSession, handle);
        } catch (RuntimeException e) {
            LOG.warn("子代理回合失败: parent={} agent={}", call.getParentSessionId(), call.getAgentId(), e);
            // 子会话可能已建、run 可能已登记：带着它们返回，交给 await 统一收尾
            return SubAgentRunHandle.failed(call.getParentSessionId(), child, runId,
                    SubAgentOutcome.failed(messageOf(e)));
        }
    }

    /**
     * 归档一次 run，失败只记 WARN。
     * <p>
     * <b>为什么在收尾里做而不是在主流程里</b>：归档是每一个终局（成功 / 失败 / 取消 / 超时）
     * 都该留下的痕迹，而 {@code finally} 是唯一覆盖全部这四个出口的位置。
     *
     * @param runId run 标识，可为 {@code null}（未派生成功）
     * @param child 子会话，可为 {@code null}
     */
    private void archiveQuietly(String runId, Session child) {
        if (runId == null) {
            return;
        }
        try {
            archive.archive(runtime.snapshot(runId).orElse(null), child);
        } catch (RuntimeException e) {
            // 归档失败不该把一次委派升级成失败，但也不该静默：这是“过程没有留下痕迹”的唯一线索
            LOG.warn("run 归档异常: runId={} reason={}", runId, e.toString());
        }
    }

    /**
     * 把 ReAct 回合终态翻译成 run 状态。
     * <p>
     * <b>为什么 {@code BLOCKED} 与工具结果口径不同</b>：被回合开始前钩子拦下时，今天的
     * {@link #toOutcome(ReActResult, Session)} 仍按「已完成」渲染（既有行为，本步不改）。
     * run 状态如实记 {@code BLOCKED} 供观测，两者的对齐留到结果类型统一那一步。
     *
     * @param result 回合结果
     * @return run 状态，保证为终态
     */
    private static AgentRunStatus runStatusOf(ReActResult result) {
        if (result.isCancelled()) {
            return AgentRunStatus.CANCELLED;
        }
        if (result.isTruncated()) {
            return AgentRunStatus.TRUNCATED;
        }
        if (result.isBlocked()) {
            return AgentRunStatus.BLOCKED;
        }
        return AgentRunStatus.DONE;
    }

    /**
     * 组装子代理这一轮能看到哪些工具的过滤。
     * <p>
     * 判据取自 {@code PermissionManager}，与执行期判定同一份规则：子代理只该看到自己那份 agent 配置
     * 允许的工具，不该被邀请去调用一个会被拒的东西。但<a>主会话的工具清单不受影响</a>——
     * 过滤只随嵌套回合传递。
     * <p>
     * <b>这里只有 agent 策略，没有模式类策略</b>：按模式收窄的工具（例如 plan 模式）是插件在
     * 执行期用权限扩展点表达的，它不在清单过滤的判据里，因此子代理的清单不会因父会话开着某个模式而变化。
     *
     * @param call   委派请求
     * @param parent 父会话运行态
     * @return 过滤器，保证非 {@code null}
     */
    private ToolFilter toolFilterOf(SubAgentCall call, Session parent) {
        return ToolFilter.of(permissionManager.usableTools(call.getAgentId()));
    }

    /**
     * 把子代理回合的终态翻译成委派结果。
     *
     * @param result 子代理回合结果
     * @param child  子会话运行态
     * @return 委派结果，保证非 {@code null}
     */
    private static SubAgentOutcome toOutcome(AgentRunResult result, Session child) {
        switch (result.getStatus()) {
            case CANCELLED:
                return SubAgentOutcome.cancelled(result.getRounds(), child.getUsage());
            case TRUNCATED:
                String reason = truncationReasonOf(result);
                return SubAgentOutcome.truncated(truncatedText(reason, child), result.getRounds(),
                        child.getUsage(), reason);
            case FAILED:
                return SubAgentOutcome.failed(result.getError());
            default:
                // DONE 与 BLOCKED 同走这条：被钩子拦下时仍按「已完成」渲染，是既有行为
                return SubAgentOutcome.completed(result.getText(), result.getRounds(), child.getUsage());
        }
    }

    /**
     * 组装未收敛时的回灌文本：原因 + 子代理最后一段已产出的正文。
     * <p>
     * <b>为什么原因必须打头</b>：未收敛有两种形状完全一样的来源——被墙钟掐断与被轮数/预算截断，
     * 它们下一步该做什么完全不同（延长墙钟 vs 拆小任务）。原因句本身就是「该调哪个键」，
     * 放在开头才能让主会话（以及读这一行的人）不必读完正文就知道发生了什么。
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
     * @param reason 未收敛的原因，可为 {@code null}
     * @param child  子会话运行态
     * @return 回灌文本，保证非 {@code null}
     */
    private static String truncatedText(String reason, Session child) {
        String hint = reason == null ? "" : reason.trim();
        String text = lastAssistantText(child);
        if (StringUtils.isBlank(text)) {
            // 一句正文都没写：只留原因，不为「空内容」另编一句说明
            return hint;
        }
        return hint + "\n（以下是它最后一段已产出的正文，更早的轮次未一并回灌）\n" + text;
    }

    /**
     * 取未收敛的原因。
     * <p>
     * <b>两条来源都要看</b>：墙钟到点由调度器把原因写在 {@code error} 上——那时回合是被取消的，
     * 既没有正文、也没有循环器给的内核提示；而轮数上限与 token 预算由循环器写在 {@code text} 上
     * （它本来就是那句「请调大哪个键」的提示）。只看其中之一，就会有一半的未收敛说不出为什么，
     * 而「为什么停的」正是主会话决定下一步该怎么做的唯一依据。
     *
     * @param result run 的终态结果，不可为 {@code null}
     * @return 原因文本，保证非空
     */
    private static String truncationReasonOf(AgentRunResult result) {
        if (StringUtils.isNotBlank(result.getError())) {
            return result.getError().trim();
        }
        if (StringUtils.isNotBlank(result.getText())) {
            return result.getText().trim();
        }
        return UNKNOWN_TRUNCATION_REASON;
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
    private static String limitReason(RunContext scope) {
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
