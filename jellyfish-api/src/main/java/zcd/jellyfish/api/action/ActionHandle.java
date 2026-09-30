package zcd.jellyfish.api.action;

/**
 * 动作句柄：插件投递动作之后拿到的唯一凭据，用来<b>轮询</b>它的结果。
 * <p>
 * <b>为什么是轮询而不是回调</b>：见 {@link ActionStatus}。插件在自己的线程上查状态，
 * 内核因此永远不需要回头调插件——「插件不能同步回调内核」这条纪律靠这个方向性成立。
 * <p>
 * <b>它不是 {@code Future}，也不阻塞</b>：没有 {@code get()}、没有超时、不能取消。
 * 要中止一个已经投递的动作，用 {@link PluginAction#abortTurn(String)}；
 * 要中止整个回合，那是用户主权，不是插件动作。
 * <p>
 * 线程安全：内核从多个线程更新状态，插件从自己的线程读取；实现必须保证可见性。
 *
 * @author zcd
 */
public interface ActionHandle {

    /**
     * 获取被投递的动作。
     *
     * @return 动作，保证非 {@code null}
     */
    PluginAction getAction();

    /**
     * 获取当前状态。
     *
     * @return 状态，保证非 {@code null}
     */
    ActionStatus getStatus();

    /**
     * 获取结果说明。
     * <p>
     * 未结束时为 {@code null}；{@link ActionStatus#DONE} 时是执行摘要（可为 {@code null}，
     * 有些动作本来就没有可说的结果）；{@link ActionStatus#FAILED} 与 {@link ActionStatus#DROPPED}
     * 时是失败 / 丢弃的原因。
     *
     * @return 结果文本，未结束时为 {@code null}
     */
    String getResult();

    /**
     * 判断是否已有终态。
     *
     * @return 处于终态返回 {@code true}
     */
    default boolean isFinished() {
        ActionStatus status = getStatus();
        return status == ActionStatus.DONE || status == ActionStatus.FAILED || status == ActionStatus.DROPPED;
    }
}
