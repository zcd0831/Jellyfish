package zcd.jellyfish.infra.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 广播线程池拒绝策略：丢弃 + 计数 + 限流告警。
 * <p>
 * <b>刻意不做「降级为调用线程内联执行」</b>：异步侧的契约本来就是「允许丢弃」，内联降级会把订阅者
 * 拖回 ReAct 调用线程，既违反契约又可能长时间阻塞工具调用。因此不使用 JDK 的 {@code CallerRunsPolicy}；
 * 也不使用 {@code DiscardPolicy}（静默丢弃不可接受）——本类保证每次丢弃都留下计数与一条 WARN。
 *
 * @author zcd
 */
final class EventRejectedExecutionHandler implements RejectedExecutionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(EventRejectedExecutionHandler.class);

    /** 指标引用。 */
    private final EventChannelStats stats;

    /**
     * 构造拒绝策略。
     *
     * @param stats 指标引用
     */
    EventRejectedExecutionHandler(EventChannelStats stats) {
        this.stats = stats;
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        stats.droppedEvents.increment();
        LOG.warn("通知队列已满，通知被丢弃: queueSize={} caller={}", executor.getQueue().size(),
                Thread.currentThread().getName());
    }
}
