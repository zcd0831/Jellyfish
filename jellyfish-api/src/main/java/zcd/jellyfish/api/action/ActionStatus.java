package zcd.jellyfish.api.action;

/**
 * 动作的执行状态。
 * <p>
 * <b>轮询式，没有回调</b>：与既有的 {@code InputDirectiveRun}、压缩状态同形态。插件在自己的线程上
 * 轮询 {@link ActionHandle#getStatus()}，内核因此不需要在完成时回头调插件——
 * 「插件不能同步回调内核」这条纪律的另一面就是「内核也不回调插件」，两边都不留重入点。
 *
 * @author zcd
 */
public enum ActionStatus {

    /** 已受理，排在待排空队列里，还没被内核取走。 */
    QUEUED,

    /** 内核正在执行它。 */
    EXECUTING,

    /** 执行完成。 */
    DONE,

    /**
     * 执行失败：没有在途回合、目标会话不存在、载荷非法、执行体抛错。
     * <p>
     * <b>它是常态而不是异常</b>：插件从事件订阅回调（异步投递、可能落在回合刚结束之后）或自己的线程上
     * 投递动作时，失败是正常结果。插件必须把它当「这次没成」处理，而不是当 bug。
     * <p>
     * 人可读的原因见 {@link ActionHandle#getResult()}；<b>要分流必须看</b>
     * {@link ActionHandle#getFailureReason()}——本状态覆盖好几种来路不同的处境，
     * 只按状态分流会把「轮次已用尽」当成可重试。
     */
    FAILED,

    /**
     * 被丢弃，没有执行。
     * <p>
     * 两种来源：① 待排空队列已满（每会话有上界），新动作被丢；② 插件停止时它在途排队的动作被整批清掉。
     * 两者分别由 {@link ActionFailureReason#QUEUE_FULL} 与 {@link ActionFailureReason#PLUGIN_STOPPED}
     * 区分，因此「等队列空出来再投」与「别投了」是可以编程分辨的。
     * <p>
     * 丢弃是安全的：动作是「建议内核做事」，不是「必须完成的事实」。
     */
    DROPPED
}
