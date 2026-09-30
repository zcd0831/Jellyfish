package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 回合开始前请求：内核在<b>追加用户消息之前</b>询问「这个回合要不要起、输入要不要改」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：每一次回合（含嵌套回合）
 * 都会问一遍，不按会话或 agent 分槽。需要按 agent 分流时，处理器自己看 {@link #getAgentId()}。
 * <p>
 * <b>为什么调用点必须在追加消息之前</b>：一旦用户消息进了会话，拦下就只剩「把消息再删掉」这条路，
 * 而会话历史是 append-only 的（缓存前缀与落盘都依赖这条性质）。在追加之前拦，历史里干干净净，
 * 用户重发一次就好。
 * <p>
 * <b>顶层与嵌套共用同一个入口</b>：{@code runNested} 与顶层回合走同一条校验路径，否则子代理路径
 * 会绕过钩子——而子代理恰恰是最需要「脏仓库守护」这类拦截的地方。
 * <p>
 * <b>拦下不是安全边界</b>：它拦的是「这次回合要不要跑」，而不是某个工具调用要不要跑。
 * 该走的权限判定与审批一次都不会少。
 * <p>
 * <b>失败语义由调用点决定</b>：handler 抛错按「放行」处理（记 WARN）——拦截点坏掉时宁可放行，
 * 也不要让整个会话彻底不能用。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnBeforeRequest extends ExtensionRequest<TurnDirective> {

    /** 会话所属的 agentId，未绑定时为 {@code null}。 */
    private final String agentId;

    /** 本次回合的输入文本。 */
    private final String input;

    /** 是否嵌套回合（子代理委派）。 */
    private final boolean nested;

    /** 委派深度：顶层回合为 {@code 0}。 */
    private final int depth;

    /** 当时的会话权限模式。 */
    private final PermissionMode mode;

    /**
     * 构造回合开始前请求。
     * <p>
     * {@code mode} 为空时按 {@link PermissionMode#NORMAL} 处理，避免调用点与处理器到处判空。
     *
     * @param sessionId 会话标识，不可为空白
     * @param agentId   会话所属的 agentId，可为 {@code null}
     * @param input     本次回合的输入文本，可为 {@code null}
     * @param nested    是否嵌套回合
     * @param depth     委派深度
     * @param mode      会话权限模式，可为 {@code null}
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public TurnBeforeRequest(String sessionId, String agentId, String input, boolean nested, int depth,
                             PermissionMode mode) {
        super(TurnDirective.class, sessionId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("session id must not be blank");
        }
        this.agentId = agentId;
        this.input = input;
        this.nested = nested;
        this.depth = depth;
        this.mode = mode == null ? PermissionMode.NORMAL : mode;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取会话所属的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取本次回合的输入文本。
     *
     * @return 输入文本，可能为 {@code null}
     */
    public String getInput() {
        return input;
    }

    /**
     * 判断是否嵌套回合。
     * <p>
     * 为真时内核会忽略 {@link TurnDirective#replaceInput(String)}——嵌套回合的输入是模型写出来的
     * 任务描述，改写它会让「模型要什么」与「子代理收到什么」分叉。
     *
     * @return 嵌套回合返回 {@code true}
     */
    public boolean isNested() {
        return nested;
    }

    /**
     * 获取委派深度。
     *
     * @return 委派深度，顶层回合为 {@code 0}，保证非负
     */
    public int getDepth() {
        return depth;
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
