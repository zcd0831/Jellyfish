package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;

/**
 * 权限判定审计事件：每次判定结束后广播，<b>放行也发</b>。
 * <p>
 * 只观察、不参与判定：订阅方（日志、指标、TUI）拿到的永远是已生效的结果，改不了权限。
 * 事件通道是 best-effort（有界队列、队列满即丢弃），因此本事件用于可观测性，
 * 不承担审计级的可靠性保证。
 *
 * @author zcd
 */
public final class PermissionDecidedEvent extends AbstractJellyfishEvent {

    /** 发起调用的 agentId，未绑定时为 {@code null}。 */
    private final String agentId;

    /** 被检查的工具名。 */
    private final String toolName;

    /** 判定时的会话权限模式。 */
    private final PermissionMode mode;

    /** 最终判定结论。 */
    private final PermissionDecision.Outcome outcome;

    /** 判定理由，可为 {@code null}。 */
    private final String reason;

    /** 判定来源：核心策略判定为 {@code "core"}，插件拦截为拦截插件的 pluginId。 */
    private final String source;

    /**
     * 构造权限判定事件。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  被检查的工具名
     * @param mode      判定时的会话权限模式
     * @param outcome   最终判定结论（ASK 已由内核降级，不会出现在事件里）
     * @param reason    判定理由，可为 {@code null}
     * @param source    判定来源：{@code "core"} 或拦截插件的 pluginId
     * @param sessionId 会话标识，可为 {@code null}
     */
    public PermissionDecidedEvent(String agentId, String toolName, PermissionMode mode,
                                  PermissionDecision.Outcome outcome, String reason, String source,
                                  String sessionId) {
        super(sessionId);
        this.agentId = agentId;
        this.toolName = toolName;
        this.mode = mode;
        this.outcome = outcome;
        this.reason = reason;
        this.source = source;
    }

    /**
     * 获取发起调用的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取被检查的工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取判定时的会话权限模式。
     *
     * @return 权限模式
     */
    public PermissionMode getMode() {
        return mode;
    }

    /**
     * 获取最终判定结论。
     *
     * @return 判定结论
     */
    public PermissionDecision.Outcome getOutcome() {
        return outcome;
    }

    /**
     * 获取判定理由。
     *
     * @return 判定理由，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取判定来源。
     *
     * @return {@code "core"} 或拦截插件的 pluginId
     */
    public String getSource() {
        return source;
    }
}
