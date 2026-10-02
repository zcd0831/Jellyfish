package zcd.jellyfish.api.action;

/**
 * 动作的失败原因码：{@link ActionHandle#getFailureReason()} 的取值。
 * <p>
 * <b>为什么要有它</b>：{@link ActionStatus#FAILED} 与 {@link ActionStatus#DROPPED} 各自都覆盖好几种
 * 来路完全不同的处境——「现在没有回合在跑」与「本回合轮次已用尽」对插件是两回事，前者换个时刻再投
 * 有意义，后者在本回合内再投一百次也一样。只靠 {@link ActionHandle#getResult()} 那句人可读的原因
 * 去分流，就等于让插件解析中文串。
 * <p>
 * <b>判据是「再投一次有没有意义」，不是「哪一行代码报的」</b>：插件据此决定重试、降级还是放弃。
 * 因此同一个取值可能由多个地方产生（例如会话不存在与只有子代理回合都归 {@link #NO_TURN_IN_FLIGHT}），
 * 而差异留在人可读的原因里。
 * <p>
 * <b>它不是异常类型</b>：失败是动作通道的常态（见 {@link ActionStatus}），因此它不配栈、不抛出，
 * 只是句柄上的一个字段。
 *
 * @author zcd
 * @see ActionHandle#getFailureReason()
 */
public enum ActionFailureReason {

    /**
     * 投递时该会话没有在途的顶层回合。
     * <p>
     * 同一取值还覆盖「会话不存在」与「该会话只有子代理（嵌套）回合」——后两者同样没有顶层回合，
     * 因此同样没有投递窗口。三者中只有第一种会随回合到来而改变，但插件无法从原因上区分，
     * 因此按「换个时刻再投」处理即可。
     */
    NO_TURN_IN_FLIGHT,

    /**
     * 动作排到了排空点，但本回合已经没有剩余轮次把新消息发给模型。
     * <p>
     * 在本回合内重投没有意义（轮次不会增长）；会话的下一个回合是新的窗口。
     */
    NO_REMAINING_ROUNDS,

    /**
     * 压缩不可用：没有任何插件提供压缩策略。
     * <p>
     * 与用户敲 {@code /compact} 见到的失败同源。装上策略插件后重投才有意义。
     */
    COMPACTION_UNAVAILABLE,

    /**
     * 执行体抛错。
     * <p>
     * 具体异常类型与消息在 {@link ActionHandle#getResult()} 里。重新投一条同样的动作通常同样会失败。
     */
    EXECUTION_ERROR,

    /**
     * 内核没有接上该动作种类的执行分支。
     * <p>
     * 这只可能是内核缺陷（新增了 {@link PluginAction.Kind} 却漏了执行分支），不是插件用错。
     */
    UNSUPPORTED_KIND,

    /**
     * 每会话待排空队列已满，本条被丢弃。
     * <p>
     * 对应 {@link ActionStatus#DROPPED}。等队列被排空后重投有意义。
     */
    QUEUE_FULL,

    /**
     * 投递者已停止，在途动作被整批丢弃。
     * <p>
     * 对应 {@link ActionStatus#DROPPED}。按 owner 命名空间回收：不只本插件自己的动作，
     * 它派生的子命名空间（{@code pluginId::child}）也一并丢弃。
     */
    PLUGIN_STOPPED,

    /**
     * 回合在动作被排空前就结束了。
     * <p>
     * 典型来源是插件从事件订阅回调投递，而事件是异步派发的，正好落在回合收敛之后。
     * 本回合内已无可能被排空，重投只会落到下一个回合（那是另一件事）。
     */
    TURN_ENDED_UNREACHED,

    /**
     * 回合窗口被同一会话的另一个顶层回合取代，旧窗口里的动作再也不会被排空。
     * <p>
     * 与 {@link #TURN_ENDED_UNREACHED} 的区别在于：取代它的新回合可能仍在跑，
     * 因此此刻重投有机会落进新窗口。
     */
    TURN_SUPERSEDED
}
