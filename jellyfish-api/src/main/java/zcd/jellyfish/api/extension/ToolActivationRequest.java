package zcd.jellyfish.api.extension;

/**
 * 工具激活请求：内核在为某个会话<b>冻结</b>工具清单的那一刻，对每个已注册的工具问一次
 * 「它该不该进这份清单」。
 * <p>
 * <b>为什么需要这个扩展点</b>：工具清单按会话冻结是缓存前缀保证的一部分（清单在多数厂商的模板里
 * 排在 messages 之前，变一个字则整段请求连同历史全部作废），而这条纪律此前<b>只由内核执行</b>——
 * 插件没有任何表达「这个工具现在不该出现」的正式手段。装了 MCP 插件之后工具全量进清单，
 * 用户无法让其中几个「先别进请求」，只能看着它们占着前几个 token 的位置并参与模型的每次推理。
 * <p>
 * <b>只在冻结点求值一次，不是每轮</b>：每轮问一次就等于把「清单逐轮可变」放回来了，
 * 与冻结保证直接冲突。因此注册表在会话中途的变化（MCP 的 {@code tools/list_changed} 重扫）
 * <b>对已有会话无效</b>，只影响新会话；想让已有会话跟上，走
 * {@code PluginAction.rebuildToolCatalog(sessionId, reason)} 这条显式动作，
 * 并接受一次前缀断裂的代价。
 * <p>
 * <b>路由键为 {@code null}，用 {@code contribute}</b>：一个工具该不该出现可以由多个插件各自表态
 * （有的按服务是否连通、有的按当前模式），因此它是类型级贡献。合并规则：
 * <b>第一个非 {@link ToolActivation#abstain()} 的判定胜出</b>（{@code order} 升序），
 * 与 {@link CompactionStrategyRequest}、{@link RequestTuningRequest} 的「调用点 for 循环 + 取第一个表态者」
 * 同一口径——注册表不参与编排。
 * <p>
 * <b>0 个处理器时全部可见</b>，与非插件路径逐字段一致。
 * <p>
 * <b>失败语义</b>：处理器抛错 → 只记 WARN，该工具<b>保持可见</b>。理由：失败时保留工具比隐藏工具安全
 * ——隐藏会让模型「不知道有这个能力」而走进死路，保留只是多几个 token（与压缩、请求调优的
 * 「失败走保守那一侧」同向，但保守的方向由各自的代价决定）。
 * <p>
 * <b>对子代理同样生效</b>：子代理回合是同一个工具目录的另一个会话，因此它的清单冻结走的是同一次求值；
 * 子代理再按自己的 agent 配置叠加 {@code ToolFilter} 收窄。两者叠加而不是二选一——
 * 「主代理看不到、子代理却看得到」会产生无法解释的不对称，而子代理本该只拿回结论。
 * <p>
 * <b>实现约定</b>：由内核在调用点线程同步派发，且发生在组装请求的过程中，因此处理器必须<b>快且只读</b>
 * ——不要做 I/O、不要阻塞、不要发布事件。需要探测外部服务时应当在插件自己的后台线程上做，
 * 把结论缓存在内存里。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolActivationRequest extends ExtensionRequest<ToolActivation> {

    /** 会话标识；瞬时（子代理）会话也是真实会话，因此同样有值。 */
    private final String sessionId;

    /** 当前会话的 agent 标识，插件据此区别对待主代理与子代理。 */
    private final String agentId;

    /** 工具名，即路由到执行实现的那个名字。 */
    private final String toolName;

    /** 工具描述符，插件无需再去注册表回查。 */
    private final ToolDescriptor descriptor;

    /** 当前会话的权限模式。 */
    private final PermissionMode permissionMode;

    /**
     * 构造请求。
     *
     * @param sessionId      会话标识，可为 {@code null}
     * @param agentId        agent 标识，可为 {@code null}
     * @param descriptor     工具描述符，不可为 {@code null}
     * @param permissionMode 权限模式，可为 {@code null}
     */
    public ToolActivationRequest(String sessionId, String agentId, ToolDescriptor descriptor,
                                 PermissionMode permissionMode) {
        super(ToolActivation.class, sessionId);
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.descriptor = descriptor;
        this.toolName = descriptor == null ? null : descriptor.getName();
        this.permissionMode = permissionMode;
    }

    @Override
    public String getRouteKey() {
        // 类型级扩展点：同一工具允许多个插件各表一部分态度，由内核按 order 取第一个表态者
        return null;
    }

    /**
     * 获取 agent 标识。
     *
     * @return agent 标识，可能为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取工具名。
     * <p>
     * 与 {@link #getDescriptor()}{@code .getName()} 同源，单独给一份是因为
     * 「这个工具叫什么」是判定里最常读的一个字段。
     *
     * @return 工具名，描述符缺失时为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具描述符。
     *
     * @return 工具描述符，可能为 {@code null}
     */
    public ToolDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * 获取权限模式。
     *
     * @return 权限模式，可能为 {@code null}
     */
    public PermissionMode getPermissionMode() {
        return permissionMode;
    }
}
