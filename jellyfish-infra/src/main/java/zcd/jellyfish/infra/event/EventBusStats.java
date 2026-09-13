package zcd.jellyfish.infra.event;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

/**
 * 事件总线指标：全部使用 {@link LongAdder} 累加。
 * <p>
 * <b>自身指标不通过事件总线上报</b>，避免「上报指标本身产生事件」的自指循环；
 * 需要展示时由上层主动调用 {@link #render()}。
 *
 * @author zcd
 */
public final class EventBusStats {

    /** 已发布的通知数量。 */
    final LongAdder publishedEvents = new LongAdder();

    /** 被丢弃的通知数量。 */
    final LongAdder droppedEvents = new LongAdder();

    /** 无 dispatcher 订阅的死事件类型数量。 */
    final LongAdder deadEventTypes = new LongAdder();

    /** 有 dispatcher 但无订阅者命中的通知数量。 */
    final LongAdder unmatchedNotifications = new LongAdder();

    /** 订阅者异常数量。 */
    final LongAdder subscriberErrors = new LongAdder();

    /** 已派发的回调数量。 */
    final LongAdder dispatchedCallbacks = new LongAdder();

    /** 失败的回调数量。 */
    final LongAdder failedCallbacks = new LongAdder();

    /** 无处理器的回调数量。 */
    final LongAdder noHandlerCallbacks = new LongAdder();

    /** 处理器不唯一的回调数量。 */
    final LongAdder ambiguousHandlerCallbacks = new LongAdder();

    /** 因嵌套过深被拒绝的回调数量。 */
    final LongAdder nestingRejectedCallbacks = new LongAdder();

    /** 启动期缓冲回放的通知数量。 */
    final LongAdder pendingReplayed = new LongAdder();

    /** 启动期缓冲溢出被丢弃的通知数量。 */
    final LongAdder pendingOverflow = new LongAdder();

    /** 通知线程池，用于读取活动线程数与队列长度；未绑定时对应指标为 0。 */
    private volatile ThreadPoolExecutor executor;

    /**
     * 绑定通知线程池。
     *
     * @param executor 通知线程池
     */
    void bindExecutor(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    /**
     * 获取已发布的通知数量。
     *
     * @return 通知数量
     */
    public long getPublishedEvents() {
        return publishedEvents.sum();
    }

    /**
     * 获取被丢弃的通知数量。
     *
     * @return 通知数量
     */
    public long getDroppedEvents() {
        return droppedEvents.sum();
    }

    /**
     * 获取无 dispatcher 订阅的死事件类型数量。
     *
     * @return 类型数量
     */
    public long getDeadEventTypes() {
        return deadEventTypes.sum();
    }

    /**
     * 获取无订阅者命中的通知数量。
     *
     * @return 通知数量
     */
    public long getUnmatchedNotifications() {
        return unmatchedNotifications.sum();
    }

    /**
     * 获取订阅者异常数量。
     *
     * @return 异常数量
     */
    public long getSubscriberErrors() {
        return subscriberErrors.sum();
    }

    /**
     * 获取已派发的回调数量。
     *
     * @return 回调数量
     */
    public long getDispatchedCallbacks() {
        return dispatchedCallbacks.sum();
    }

    /**
     * 获取失败的回调数量。
     *
     * @return 回调数量
     */
    public long getFailedCallbacks() {
        return failedCallbacks.sum();
    }

    /**
     * 获取无处理器的回调数量。
     *
     * @return 回调数量
     */
    public long getNoHandlerCallbacks() {
        return noHandlerCallbacks.sum();
    }

    /**
     * 获取处理器不唯一的回调数量。
     *
     * @return 回调数量
     */
    public long getAmbiguousHandlerCallbacks() {
        return ambiguousHandlerCallbacks.sum();
    }

    /**
     * 获取因嵌套过深被拒绝的回调数量。
     *
     * @return 回调数量
     */
    public long getNestingRejectedCallbacks() {
        return nestingRejectedCallbacks.sum();
    }

    /**
     * 获取启动期缓冲回放的通知数量。
     *
     * @return 通知数量
     */
    public long getPendingReplayed() {
        return pendingReplayed.sum();
    }

    /**
     * 获取启动期缓冲溢出被丢弃的通知数量。
     *
     * @return 通知数量
     */
    public long getPendingOverflow() {
        return pendingOverflow.sum();
    }

    /**
     * 获取当前活动线程数。
     *
     * @return 活动线程数
     */
    public int getActiveThreads() {
        ThreadPoolExecutor current = executor;
        return current == null ? 0 : current.getActiveCount();
    }

    /**
     * 获取当前队列长度。
     *
     * @return 排队任务数
     */
    public int getQueueSize() {
        ThreadPoolExecutor current = executor;
        return current == null ? 0 : current.getQueue().size();
    }

    /**
     * 渲染指标快照。
     *
     * @return 可读的指标文本
     */
    public String render() {
        return "eventBusStats{"
                + "publishedEvents=" + getPublishedEvents()
                + ", droppedEvents=" + getDroppedEvents()
                + ", deadEventTypes=" + getDeadEventTypes()
                + ", unmatchedNotifications=" + getUnmatchedNotifications()
                + ", subscriberErrors=" + getSubscriberErrors()
                + ", dispatchedCallbacks=" + getDispatchedCallbacks()
                + ", failedCallbacks=" + getFailedCallbacks()
                + ", noHandlerCallbacks=" + getNoHandlerCallbacks()
                + ", ambiguousHandlerCallbacks=" + getAmbiguousHandlerCallbacks()
                + ", nestingRejectedCallbacks=" + getNestingRejectedCallbacks()
                + ", pendingReplayed=" + getPendingReplayed()
                + ", pendingOverflow=" + getPendingOverflow()
                + ", activeThreads=" + getActiveThreads()
                + ", queueSize=" + getQueueSize()
                + '}';
    }
}
