package zcd.jellyfish.infra.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 通知线程池拒绝策略：能兜就兜，不能兜就记账。
 * <ol>
 *     <li>当前线程不是事件线程 → 降级为调用线程内联执行，保证关键通知不丢；</li>
 *     <li>当前线程是事件线程 → 丢弃 + {@code droppedEvents} 计数 + 限流 WARN，避免递归放大；</li>
 * </ol>
 * 不使用 JDK 内置的 {@code CallerRunsPolicy}（会让 ReAct 线程跑订阅者而长时间阻塞），
 * 也不使用 {@code DiscardPolicy}（静默丢弃不可接受）。
 *
 * @author zcd
 */
final class EventRejectedExecutionHandler implements RejectedExecutionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventRejectedExecutionHandler.class);

    /** 指标引用。 */
    private final EventBusStats stats;

    /**
     * 构造拒绝策略。
     *
     * @param stats 指标引用
     */
    EventRejectedExecutionHandler(EventBusStats stats) {
        this.stats = stats;
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        if (Thread.currentThread().getName().startsWith(EventThreadFactory.NAME_PREFIX)) {
            stats.droppedEvents.increment();
            LOG.warn("通知队列已满，事件线程内的通知被丢弃: queueSize={}", executor.getQueue().size());
            return;
        }
        LOG.warn("通知队列已满，降级为调用线程内联执行: thread={}", Thread.currentThread().getName());
        runnable.run();
    }
}
