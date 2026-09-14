package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权限检查请求：内核在调用点构造，询问「本次工具调用能否执行」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：拦截针对的是「一次工具调用」这个
 * 整体判断，不按工具名分槽。若按工具名分槽，两个插件都想拦同一个工具就会撞「同键唯一」，
 * 与「闸门对所有插件开放」的目标冲突。
 * <p>
 * 携带 {@code arguments} 是刻意的：拦截策略常常要看参数（例如命令里出现危险操作），
 * 而拦截发生在执行之前、参数已经齐备。
 * <p>
 * 结果类型是 {@link PermissionVeto} 而不是 {@link PermissionDecision}：插件只能表达「不拦截 / 拦截」，
 * 判定结论（含 {@code ASK}）只能由内核给出。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PermissionCheckRequest extends ExtensionRequest<PermissionVeto> {

    /** 发起调用的 agentId，未绑定 agent 时为 {@code null}。 */
    private final String agentId;

    /** 待检查的工具名。 */
    private final String toolName;

    /** 工具参数，只读。 */
    private final Map<String, Object> arguments;

    /** 当时的会话权限模式。 */
    private final PermissionMode mode;

    /**
     * 构造权限检查请求。
     * <p>
     * {@code agentId} 允许为空：未绑定 agent 时视为「无策略」，由内核按 fail-open 处理，
     * 因此这里不做校验；{@code mode} 为空时按 {@link PermissionMode#NORMAL} 处理，
     * 避免调用点与处理器到处判空。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  待检查的工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @param mode      会话权限模式，可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public PermissionCheckRequest(String agentId, String toolName, Map<String, Object> arguments,
                                  PermissionMode mode, String sessionId) {
        super(PermissionVeto.class, sessionId);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.agentId = agentId;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.mode = mode == null ? PermissionMode.NORMAL : mode;
    }

    /**
     * 构造常规模式下的进程级请求（无会话）。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  待检查的工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public PermissionCheckRequest(String agentId, String toolName, Map<String, Object> arguments) {
        this(agentId, toolName, arguments, null, null);
    }

    @Override
    public String getRouteKey() {
        return null;
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
     * 获取待检查的工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取会话权限模式。
     *
     * @return 权限模式，保证非 {@code null}
     */
    public PermissionMode getMode() {
        return mode;
    }
}
