package zcd.jellyfish.core.runtime;

/**
 * 一个 agent run 的终态与进行中状态。
 * <p>
 * <b>为什么单独一个枚举而不是复用 {@code SubAgentStatus}</b>：{@code SubAgentStatus} 是「工具结果」的
 * 展示词汇（描述这次委派对模型意味着什么），本类型是「运行时单元」的状态（描述这个 run 处在生命周期的哪一步），
 * 两者受众不同，且运行时要多出 {@code PENDING} / {@code RUNNING} / {@code WAITING_CHILDREN} 这三个
 * 只对调度有意义、永远不会出现在工具结果里的值。合成一个会让「工具结果为什么会有 RUNNING」变成需要解释的问题。
 * <p>
 * <b>{@link #BLOCKED} 与 {@code SubAgentStatus} 没有对应项</b>：被回合开始前的钩子拦下时，
 * 今天工具结果仍按「已完成」渲染（沿用既有行为）。本枚举如实记录 {@code BLOCKED} 供观测使用，
 * 两者的对齐留到结果类型统一那一步，不在本步顺手改用户可见行为。
 *
 * @author zcd
 */
public enum AgentRunStatus {

    /** 已登记、尚未开始执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 正在等待它的子 run 返回（仅深度 &gt; 1 时出现）。 */
    WAITING_CHILDREN,

    /** 正常收敛。 */
    DONE,

    /** 执行过程中抛错。 */
    FAILED,

    /** 被取消。 */
    CANCELLED,

    /** 触达轮数 / 墙钟 / token 预算而截断。 */
    TRUNCATED,

    /** 被回合开始前的钩子拦下。 */
    BLOCKED;

    /**
     * 判断是否为终态。
     * <p>
     * 终态恰好只有一个，由 {@link RunRegistry} 的 CAS 保证。
     *
     * @return 终态返回 {@code true}
     */
    public boolean isTerminal() {
        return this != PENDING && this != RUNNING && this != WAITING_CHILDREN;
    }
}
