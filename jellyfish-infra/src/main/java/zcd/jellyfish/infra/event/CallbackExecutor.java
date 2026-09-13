package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackHandler;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ISOLATED 执行器：把回调处理器提交到专用有界线程池，并对每个处理器逐个施加超时。
 * <p>
 * 与 INLINE 相比，ISOLATED 的意义是「内核能从外部打断慢处理器」：prompt 组装是同步的，
 * 一个做 3 秒向量检索的贡献者会拖垮所有会话，而内联执行无法打断。
 * <p>
 * 顺序保证：调用方（{@link CallbackDispatcher}）按 {@code order} 逐个提交并等待，因此
 * <b>保序且逐个超时</b>，而不是并发调用后排序。
 * <p>
 * 代价与边界：
 * <ol>
 *     <li>处理器不在调用线程上运行，不得依赖 {@code ThreadLocal}；</li>
 *     <li>{@code Future.cancel(true)} 只是尽力而为的中断，遇到不可中断的阻塞仍会占住工作线程，
 *         因此线程池必须有独立有界队列与拒绝策略兜底（{@link CallbackRejectedExecutionHandler}）。</li>
 * </ol>
 *
 * @author zcd
 */
final class CallbackExecutor implements AutoCloseable {

    /** 专用线程池：有界队列 + 拒绝即降级 + 守护线程。 */
    private final ThreadPoolExecutor executor;

    /** 单个处理器超时（毫秒）。 */
    private final long perHandlerTimeoutMillis;

    /** 关闭时等待排空的毫秒数。 */
    private final long shutdownAwaitMillis;

    /** 指标引用。 */
    private final EventBusStats stats;

    /**
     * 构造 ISOLATED 执行器。
     *
     * @param options 总线参数
     * @param stats   指标引用
     */
    CallbackExecutor(EventBusOptions options, EventBusStats stats) {
        this.perHandlerTimeoutMillis = options.getCallbackPerHandlerTimeoutMillis();
        this.shutdownAwaitMillis = options.getShutdownAwaitMillis();
        this.stats = Objects.requireNonNull(stats, "stats must not be null");
        this.executor = new ThreadPoolExecutor(
                options.getCallbackCorePoolSize(),
                options.getCallbackMaxPoolSize(),
                options.getCallbackKeepAliveSeconds(),
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(options.getCallbackQueueCapacity()),
                new EventThreadFactory(EventThreadFactory.CALLBACK_PREFIX),
                new CallbackRejectedExecutionHandler(stats));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 在线程池中执行单个处理器并按超时等待结果。
     *
     * @param callback 回调对象，用于异常信息
     * @param handler  处理器
     * @param <R>      结果类型
     * @return 处理器结果
     * @throws Exception 处理器抛出的原始异常、超时、被拒绝或中断
     */
    <R> R execute(Callback<R> callback, CallbackHandler<Callback<R>, R> handler) throws Exception {
        Future<R> future;
        try {
            future = executor.submit(() -> handler.handle(callback));
        } catch (RejectedExecutionException e) {
            throw new JellyfishException("ISOLATED callback rejected: " + callback.getClass().getName(), e);
        }
        try {
            return future.get(perHandlerTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            stats.timedOutCallbacks.increment();
            throw new JellyfishException("ISOLATED callback timed out after " + perHandlerTimeoutMillis
                    + "ms: " + callback.getClass().getName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new JellyfishException("interrupted while awaiting ISOLATED callback: "
                    + callback.getClass().getName(), e);
        } catch (CancellationException e) {
            throw new JellyfishException("ISOLATED callback cancelled: "
                    + callback.getClass().getName(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new JellyfishException("ISOLATED callback failed: " + callback.getClass().getName(), cause);
        }
    }

    /**
     * 获取当前排队等待执行的处理器数量。
     *
     * @return 队列长度
     */
    int queuedTaskCount() {
        return executor.getQueue().size();
    }

    /**
     * 停止接收并等待排空，超时则强制关闭。
     */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownAwaitMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
