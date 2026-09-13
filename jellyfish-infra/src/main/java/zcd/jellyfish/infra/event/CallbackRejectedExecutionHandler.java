package zcd.jellyfish.infra.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ISOLATED 回调线程池拒绝策略：拒绝即降级，不阻塞调用线程。
 * <p>
 * 队列有界是硬约束：宁可丢弃该处理器的结果并计数，也不让调用线程内联执行——
 * ISOLATED 的存在意义就是「调用方不能被慢处理器拖住」，跑回调用线程会让超时保护形同虚设。
 * 拒绝会被分发器视为一次处理器失败，由扩展点的失败语义决定是丢弃还是整次失败。
 * <p>
 * 这里必须抛出 {@link RejectedExecutionException}：{@code ThreadPoolExecutor.submit} 在拒绝时会返回一个
 * 永不完成的 Future，若不抛出，调用方只能等到超时才失败，超时保护会被当成「慢处理器」而不是「过载」。
 *
 * @author zcd
 */
final class CallbackRejectedExecutionHandler implements RejectedExecutionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CallbackRejectedExecutionHandler.class);

    /** 指标引用。 */
    private final EventBusStats stats;

    /**
     * 构造拒绝策略。
     *
     * @param stats 指标引用
     */
    CallbackRejectedExecutionHandler(EventBusStats stats) {
        this.stats = stats;
    }

    @Override
    public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
        stats.rejectedCallbacks.increment();
        LOG.warn("ISOLATED 回调队列已满，丢弃该处理器结果: queueSize={}", executor.getQueue().size());
        throw new RejectedExecutionException("ISOLATED callback queue is full");
    }
}
