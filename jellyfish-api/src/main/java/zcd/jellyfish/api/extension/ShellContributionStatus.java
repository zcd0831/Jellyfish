package zcd.jellyfish.api.extension;

/**
 * 一条外壳贡献的投递结果。
 * <p>
 * <b>为什么不是 {@code void}</b>：{@code emit} 是 {@code void}，因为它发布给未知数量的订阅者，
 * 没有「送没送到」这回事；而贡献的消费者是<b>具体的那个外壳进程</b>，
 * 插件需要知道「我这条进度是不是把队列冲爆了」「这个外壳根本就不渲染贡献」。
 * <p>
 * <b>为什么没有 {@code ActionHandle} 那样的轮询句柄</b>：贡献是即发即忘的，没有终态可等。
 * 取值保持扁平枚举（与 {@code ActionFailureReason} 同思路：机器可读的那一份），
 * 分类只有「收下了 / 合并了 / 三种没送到」。
 * <p>
 * <b>别把「没送到」当失败重试</b>：{@link #DROPPED_QUEUE_FULL} 是「这次显示没赶上」，
 * 不是「操作失败」。据此重发会把一次洪水放大成持续洪水。只有
 * {@link ShellContribution.Kind#INVALIDATED} 值得稍后重发——它是状态触发的，重发是幂等的。
 *
 * @author zcd
 */
public enum ShellContributionStatus {

    /** 已入队，稍后交付给外壳。 */
    ACCEPTED,

    /**
     * 与同 owner + 同 key 的<b>未交付</b>项合并，只保留本次这一条（原地更新）。
     * <p>
     * 已交付过的无法回收，因此合并只在队列内成立。
     */
    COALESCED,

    /**
     * 该 owner 的队列已满，本条被丢弃。
     * <p>
     * <b>丢的是最新一条，不是最旧一条</b>：留给界面的是「更近的状态」，而丢最旧会让
     * 进度类通知停在中间态上，看上去像是卡住了。
     */
    DROPPED_QUEUE_FULL,

    /**
     * {@link ShellContribution.Scope#SESSION} 的贡献没有指向一个已存在的会话。
     * <p>
     * <b>绝不因此新建会话</b>：这正是「插件不能新开会话」这条硬约束的落点之一。
     */
    DROPPED_NO_SESSION,

    /**
     * 当前外壳不渲染贡献。
     * <p>
     * 典型是 {@code -cli} 单次模式：那里没有界面，也没有人会来取队列。
     */
    DROPPED_NO_RENDERER
}
