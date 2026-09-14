package zcd.jellyfish.api.extension;

/**
 * 会话权限模式：由调用点从会话状态读出并随请求带入，进程内不存在「全局当前模式」。
 * <p>
 * 放在 {@code api} 是因为它是 {@link PermissionCheckRequest} 的字段：插件侧的拦截处理器也需要知道
 * 「本次判定发生在哪种模式下」，否则无法只在计划模式下收窄。
 *
 * @author zcd
 */
public enum PermissionMode {

    /** 常规模式：只受 agent 权限策略约束。 */
    NORMAL,

    /** 计划模式：仅允许只读工具，非只读工具一律拒绝。 */
    PLAN
}
