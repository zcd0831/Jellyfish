package zcd.jellyfish.infra.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.infra.config.PermissionApprovalSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 权限管理器：内核侧唯一的同步判定入口，回答「这次工具调用能不能执行」。
 * <p>
 * 判定分三层，顺序固定、只收紧不放宽：
 * <ol>
 *     <li><b>核心策略</b>（普通 Java 代码，不开扩展点）：agent 策略的显式拒绝 &gt; 需人工审批 &gt;
 *     允许范围收窄；</li>
 *     <li><b>插件拦截</b>：类型级扩展点，按 {@code order} 升序调用，结论取最严
 *     （{@code DENY > ASK > ABSTAIN}），遇 {@code DENY} 短路；</li>
 *     <li><b>ASK 处理</b>：经 {@link ApprovalChannel} 向审批者提问，无审批者 / 超时 / 异常一律拒绝，
 *     绝不静默放行。</li>
 * </ol>
 * 无论结果如何都会发一条 {@link PermissionDecidedEvent} 供可观测性使用（放行也发），
 * 但事件只是观察者，改不了判定结果。
 * <p>
 * <b>fail-open 的适用域</b>：只有「取不到策略」（未绑定 agent、无策略）才按放行处理；
 * 一旦策略生效，它的否定结论（显式拒绝、允许范围收窄）就是硬结论。
 * 插件侧的三态裁定同样只能收紧：它没有「放行」这一态，因此不存在插件把核心策略的拒绝改回放行的路径。
 * <p>
 * <b>模式类策略（例如只跑只读工具）不在这里</b>：它们是插件用同一个类型级扩展点表达的一条普通拦截，
 * 因此内核不持有「有哪些模式」的知识，装不装那个插件就是唯一的分界。
 * <p>
 * <b>编排写在这里是刻意的</b>：注册表只提供「有序查找」与「执行单个处理器」，调用几个、何时短路、
 * 异常怎么处置全部由本调用点决定（与「组合规则属于调用方」一致）。
 *
 * @author zcd
 */
@Singleton
public class PermissionManager {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PermissionManager.class);

    /** 核心策略判定的来源标识，写进审计事件，与插件 {@code pluginId} 区分开。 */
    public static final String CORE_SOURCE = "core";

    /** 审批结论的来源标识，写进审计事件：一眼能分出「策略直接拒绝」与「人在审批框上拒绝」。 */
    public static final String APPROVAL_SOURCE = "approval";

    /** 策略来源，将来由 AgentManager 实现。 */
    private final PermissionPolicyProvider policies;

    /** 同步扩展点策略，用于插件拦截。 */
    private final ExtensionRegistry extensions;

    /** 审计事件发布入口。 */
    private final EventPublisher events;

    /** 人工审批通道。 */
    private final ApprovalChannel approvals;

    /** 运行时配置，用于现读审批超时。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 构造权限管理器。
     *
     * @param policies      策略来源
     * @param extensions    同步扩展点策略
     * @param events        审计事件发布入口
     * @param approvals     人工审批通道
     * @param runtimeConfig 运行时配置，提供审批超时
     */
    @Inject
    public PermissionManager(PermissionPolicyProvider policies, ExtensionRegistry extensions,
                             EventPublisher events, ApprovalChannel approvals, RuntimeConfig runtimeConfig) {
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
    }

    /**
     * 判定一次工具调用是否放行。
     * <p>
     * 本方法<b>不会</b>返回 ASK：策略提出「需要人工审批」时会向审批者提问，得到批准才放行，
     * 其余情况（拒绝 / 超时 / 无审批者）一律拒绝。
     *
     * @param request 权限检查请求，不可为 {@code null}
     * @return 判定结果，保证非 {@code null}
     */
    public PermissionDecision decide(PermissionCheckRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        PermissionDecision decision = evaluatePolicy(request);
        String source = CORE_SOURCE;
        if (!decision.isDenied()) {
            // 核心策略已经拒绝时不必再问插件：结果不可能更宽，问了也只是白跑一遍插件代码
            for (HandlerBinding<PermissionCheckRequest, PermissionVerdict> binding
                    : extensions.bindings(PermissionCheckRequest.class, null)) {
                PermissionVerdict verdict = intercept(binding, request);
                if (verdict == null || verdict.isAbstain()) {
                    continue;
                }
                if (verdict.isDenied()) {
                    // DENY 一定是最严的结论，不必再问后面的插件
                    decision = PermissionDecision.deny(verdict.getReason());
                    source = binding.getOwner();
                    break;
                }
                if (!decision.isAsk()) {
                    // 同为 ASK 时保留先到者的理由：两个插件都要求审批时，原因属于「先说出来的那个」，
                    // 覆盖它只会让审计里的理由随插件顺序变化，没有信息增益
                    decision = PermissionDecision.ask(verdict.getReason());
                    source = binding.getOwner();
                }
            }
        }
        if (decision.isAsk()) {
            decision = resolveApproval(request, decision);
            source = APPROVAL_SOURCE;
        }
        publishAudit(request, decision, source);
        return decision;
    }

    /**
     * 造一个「这个 agent 在当前模式下能用哪些工具」的判据，供工具清单过滤使用。
     * <p>
     * <b>为什么复用 {@link #evaluatePolicy}</b>：清单过滤与执行期判定必须给出同一个答案——
     * 清单里出现了、执行时却被拒，模型会白跑一轮；反过来，清单里没有、执行时其实可用，
     * 模型就永远用不上它。两处各写一遍规则，迟早会在某个边界上分叉
     * （显式拒绝与允许名单的先后、ASK 算不算可用……），因此这里直接问同一个判定函数。
     * <p>
     * <b>纯判定</b>：只走核心策略，<b>不</b>询问审批、<b>不</b>派发插件拦截、<b>不</b>发审计事件。
     * 插件拦截（含各种按模式收窄的策略）与审批都是「本次调用」才能回答的问题（要看参数、要问人），
     * 无法在清单阶段预判；它们只会让调用更严，因此「清单里留着、执行时被拦下」是它们本就该有的表现。
     * <p>
     * 注意 {@code ASK} <b>不算被拒</b>：那个工具是可用的（只是要人点一下批准），
     * 从清单里拿掉会让「只读命令免打扰、写类命令要审批」这套配置直接失效。
     *
     * @param agentId agent 标识，可为 {@code null}（无策略，按 fail-open 全放行）
     * @return 判据，保证非 {@code null}
     */
    public Predicate<String> usableTools(String agentId) {
        return toolName -> !evaluatePolicy(
                new PermissionCheckRequest(agentId, toolName, null)).isDenied();
    }

    /**
     * 核心策略判定：优先级为「显式拒绝 &gt; 需审批 &gt; 允许收窄」。
     *
     * @param request 权限检查请求
     * @return 判定结果，可能是 ALLOW / DENY / ASK
     */
    private PermissionDecision evaluatePolicy(PermissionCheckRequest request) {
        PermissionPolicy policy = policies.policyOf(request.getAgentId());
        String toolName = request.getToolName();
        if (policy.denies(toolName)) {
            return PermissionDecision.deny("agent 策略显式拒绝该工具");
        }
        if (policy.requiresApproval(toolName)) {
            return PermissionDecision.ask("agent 策略要求人工审批该工具");
        }
        if (!policy.allows(toolName)) {
            return PermissionDecision.deny("工具不在 agent 允许范围内");
        }
        return PermissionDecision.allow(null);
    }

    /**
     * 执行单个插件拦截处理器。
     * <p>
     * 拦截处理器抛异常或返回 {@code null} 时按「无异议」处理：同步侧本就没有护栏，这里是调用点
     * 自己决定的那一层——一个插件的故障不应该让整条工具调用链崩掉。
     *
     * @param binding 带来源的处理器绑定
     * @param request 权限检查请求
     * @return 裁定，{@code null} 表示无异议
     */
    private PermissionVerdict intercept(HandlerBinding<PermissionCheckRequest, PermissionVerdict> binding,
                                     PermissionCheckRequest request) {
        try {
            return extensions.invoke(binding.getHandler(), request);
        } catch (RuntimeException e) {
            LOG.warn("权限拦截处理器执行失败，按无异议处理: owner={} tool={}", binding.getOwner(),
                    request.getToolName(), e);
            return null;
        }
    }

    /**
     * 处理「需要人工审批」的判定：把问题交给审批者，拿不到批准就拒绝。
     * <p>
     * 之所以不降级为放行：策略已经明确表示「这个工具要人看一眼」，审批者缺席或超时时放行
     * 等于静默放宽权限，而拒绝的代价只是工具执行失败、可被用户察觉。降级后的理由保留策略原文，
     * 让审计既能看到真实意图、又能看到实际行为。
     * <p>
     * 超时每轮现读：配置刷新后不必重启，与「不持有全局当前态」同口径。
     *
     * @param request  权限检查请求
     * @param decision 策略给出的 ASK 判定
     * @return 可执行态判定：批准为 ALLOW，其余一切情况为 DENY
     */
    private PermissionDecision resolveApproval(PermissionCheckRequest request, PermissionDecision decision) {
        PermissionApprovalSettings settings = runtimeConfig.getPermissionApprovalSettings();
        Duration timeout = Duration.ofSeconds(settings.getApprovalTimeoutSeconds());
        ApprovalChannel.Pending pending = new ApprovalChannel.Pending(request.getSessionId(),
                request.getAgentId(), request.getToolName(), request.getArguments(),
                decision.getReason());
        PermissionDecision verdict = approvals.request(pending, timeout);
        String reason = reasonOf(decision) + "；" + reasonOf(verdict);
        return verdict.isAllowed() ? PermissionDecision.allow(reason) : PermissionDecision.deny(reason);
    }

    /**
     * 取判定理由原文，空值按空串处理。
     *
     * @param decision 判定结果
     * @return 理由文本，保证非 {@code null}
     */
    private static String reasonOf(PermissionDecision decision) {
        return decision.getReason() == null ? "" : decision.getReason();
    }

    /**
     * 发布审计事件：放行也发，但失败不影响判定结果。
     *
     * @param request  权限检查请求
     * @param decision 最终判定
     * @param source   判定来源
     */
    private void publishAudit(PermissionCheckRequest request, PermissionDecision decision, String source) {
        try {
            events.publish(new PermissionDecidedEvent(request.getAgentId(), request.getToolName(),
                    decision.getOutcome(), decision.getReason(), source,
                    request.getSessionId()));
        } catch (RuntimeException e) {
            LOG.warn("权限审计事件发布失败: tool={}", request.getToolName(), e);
        }
    }
}
