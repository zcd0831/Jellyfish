package zcd.jellyfish.api.subagent;

/**
 * 一次委派的终态。
 * <p>
 * <b>为什么单独立一个枚举，而不是复用内核的 {@code SubAgentStatus}</b>：那是内核类型，插件看不到；
 * 而这个枚举的成员数量与语义是插件与内核之间的契约，必须住在 api 侧（与 {@code PluginState} 同口径）。
 * <p>
 * 与内核侧一一对应，多出来的 {@link #REJECTED} 表达的是「委派根本没有开始」——它与
 * {@link #FAILED}（开始过但失败了）对编排的意义完全不同：前者重试同一个请求没有意义，
 * 后者可能是环境问题。
 *
 * @author zcd
 */
public enum DelegationStatus {

    /** 子代理给出了最终回复。 */
    COMPLETED,

    /** 子代理达到自己的轮数上限仍未收敛。 */
    TRUNCATED,

    /** 子代理回合被取消（父回合被取消、用户中断，或编排方主动取消）。 */
    CANCELLED,

    /** 委派已经开始，但子代理回合失败（模型不可用、会话异常……）。 */
    FAILED,

    /** 委派根本没有开始：开关关闭、层数 / 预算用尽、类型未知或不可委派、内核对端不可用。 */
    REJECTED
}
