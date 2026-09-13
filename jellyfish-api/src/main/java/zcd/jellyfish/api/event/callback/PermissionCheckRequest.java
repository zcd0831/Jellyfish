package zcd.jellyfish.api.event.callback;

/**
 * 权限检查回调：请求判断某个 agent 是否可以使用某个工具。
 * <p>
 * 该回调类型<b>不标记</b> {@link PluginExtensible}：只有核心的权限管理器可以注册处理器，
 * 插件无法覆盖，避免绕过权限控制。回调类型全局唯一，因此 {@link #getRouteKey()} 返回 {@code null}。
 *
 * @author zcd
 */
@ExtensionPoint(id = "core.permission.check", shape = ExtensionShape.PROVIDE)
public final class PermissionCheckRequest extends Callback<PermissionDecision> {

    /** 发起调用的 agentId。 */
    private final String agentId;

    /** 待检查的工具名。 */
    private final String toolName;

    /**
     * 构造权限检查回调。
     *
     * @param agentId   agentId
     * @param toolName  工具名
     * @param sessionId 会话标识，可为 {@code null}
     */
    public PermissionCheckRequest(String agentId, String toolName, String sessionId) {
        super(PermissionDecision.class, sessionId, 0L);
        this.agentId = agentId;
        this.toolName = toolName;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取 agentId。
     *
     * @return agentId
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
}
