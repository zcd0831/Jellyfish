package zcd.jellyfish.infra.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVeto;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * 权限管理器：内核侧唯一的同步判定入口，回答「这次工具调用能不能执行」。
 * <p>
 * 判定分三层，顺序固定、只收紧不放宽：
 * <ol>
 *     <li><b>核心策略</b>（普通 Java 代码，不开扩展点）：agent 策略的显式拒绝 &gt; 需人工审批 &gt;
 *     允许范围收窄 &gt; PLAN 只读白名单；</li>
 *     <li><b>插件拦截</b>：类型级扩展点，按 {@code order} 升序调用，遇到拦截即短路；</li>
 *     <li><b>ASK 降级</b>：审批通道未落地前一律降级为拒绝，绝不静默放行。</li>
 * </ol>
 * 无论结果如何都会发一条 {@link PermissionDecidedEvent} 供可观测性使用（放行也发），
 * 但事件只是观察者，改不了判定结果。
 * <p>
 * <b>fail-open 的适用域</b>：只有「取不到策略」（未绑定 agent、无策略）才按放行处理；
 * 一旦策略生效，它的否定结论（PLAN 白名单、允许范围收窄）就是硬结论——否则 PLAN 模式会形同虚设。
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

    /** 审批通道未落地时给 ASK 追加的说明。 */
    private static final String APPROVAL_UNAVAILABLE = "（审批通道未落地，按拒绝处理）";

    /** 策略来源，将来由 AgentManager 实现。 */
    private final PermissionPolicyProvider policies;

    /** 只读工具集合，PLAN 模式的判据。 */
    private final ReadOnlyTools readOnlyTools;

    /** 同步扩展点策略，用于插件拦截。 */
    private final ExtensionRegistry extensions;

    /** 审计事件发布入口。 */
    private final EventPublisher events;

    /**
     * 构造权限管理器。
     *
     * @param policies      策略来源
     * @param readOnlyTools 只读工具集合
     * @param extensions    同步扩展点策略
     * @param events        审计事件发布入口
     */
    @Inject
    public PermissionManager(PermissionPolicyProvider policies, ReadOnlyTools readOnlyTools,
                             ExtensionRegistry extensions, EventPublisher events) {
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.readOnlyTools = Objects.requireNonNull(readOnlyTools, "readOnlyTools must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 判定一次工具调用是否放行。
     * <p>
     * 本方法<b>不会</b>返回 ASK：策略提出「需要人工审批」时会降级为拒绝。
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
            for (HandlerBinding<PermissionCheckRequest, PermissionVeto> binding
                    : extensions.bindings(PermissionCheckRequest.class, null)) {
                PermissionVeto veto = intercept(binding, request);
                if (veto != null && veto.isDenied()) {
                    decision = PermissionDecision.deny(veto.getReason());
                    source = binding.getOwner();
                    break;
                }
            }
        }
        decision = resolveApproval(decision);
        publishAudit(request, decision, source);
        return decision;
    }

    /**
     * 核心策略判定：优先级为「显式拒绝 &gt; 需审批 &gt; 允许收窄 &gt; PLAN 白名单」。
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
        if (request.getMode() == PermissionMode.PLAN && !readOnlyTools.contains(toolName)) {
            // PLAN 是白名单语义：集合为空时同样拒绝（属「策略已生效但集合为空」，不是「取不到策略」）
            return PermissionDecision.deny("PLAN 模式仅允许只读工具");
        }
        return PermissionDecision.allow(null);
    }

    /**
     * 执行单个插件拦截处理器。
     * <p>
     * 拦截处理器抛异常时按「无异议」处理：同步侧本就没有护栏，这里是调用点自己决定的那一层——
     * 一个插件的故障不应该让整条工具调用链崩掉。
     *
     * @param binding 带来源的处理器绑定
     * @param request 权限检查请求
     * @return 拦截裁定，{@code null} 表示无异议
     */
    private PermissionVeto intercept(HandlerBinding<PermissionCheckRequest, PermissionVeto> binding,
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
     * 处理「需要人工审批」的判定：审批通道未落地，一律降级为拒绝。
     * <p>
     * 之所以不降级为放行：策略已经明确表示「这个工具要人看一眼」，审批者缺席时放行等于静默放宽权限，
     * 而拒绝的代价只是工具执行失败、可被用户察觉。降级后的理由保留策略原文，让审计既能看到真实意图、
     * 又能看到实际行为。
     *
     * @param decision 策略给出的判定
     * @return 可执行态判定：非 ASK 原样返回，ASK 降级为 DENY
     */
    private static PermissionDecision resolveApproval(PermissionDecision decision) {
        // TODO 人工审批通道未落地：审批者永远缺席，因此 ASK 一律降级为 DENY。
        //      审批通道落地后改为「向审批者提问 → ALLOW / DENY」，ASK 才会作为终态返回；
        //      降级发生的唯一位置就是这里，调用点无需再处理 ASK。
        if (!decision.isAsk()) {
            return decision;
        }
        String reason = decision.getReason() == null ? "" : decision.getReason();
        return PermissionDecision.deny(reason + APPROVAL_UNAVAILABLE);
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
                    request.getMode(), decision.getOutcome(), decision.getReason(), source,
                    request.getSessionId()));
        } catch (RuntimeException e) {
            LOG.warn("权限审计事件发布失败: tool={}", request.getToolName(), e);
        }
    }
}
