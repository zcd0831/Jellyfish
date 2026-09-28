package zcd.jellyfish.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.infra.llm.LlmStreamHandle;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ReActTurn} 的默认实现：包级可见，只由 {@link ReActLooper} 创建。
 * <p>
 * 持有四样东西：异步任务的 {@link Future}、取消标志、当前进行中的 LLM 流句柄，以及取消回调。
 * 取消是协作式的——置标志只能阻止「下一轮 / 下一个工具」，正在进行的 LLM 流由句柄直接掐断，
 * 而正在进行的工具（命令行长调用）靠 {@link CancellationToken} 的回调被打断。
 * <p>
 * <b>它同时是取消令牌本体</b>：不另外造一个对象，是因为「回合被取消」只有一份事实，
 * 两个对象会让「谁先谁后」变成一个需要同步的问题。
 *
 * @author zcd
 */
final class ReActTurnImpl implements ReActTurn, CancellationToken {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ReActTurnImpl.class);

    /** 回合标识。 */
    private final String turnId = UUID.randomUUID().toString();

    /** 取消标志。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 取消回调，按注册顺序执行。
     * <p>
     * {@code CopyOnWriteArrayList} 是因为注册发生在 react 线程、触发发生在界面线程（按 Esc 的那一个），
     * 而回调数量极少（一次工具调用最多一个），写时复制的代价可以忽略。
     */
    private final List<Runnable> cancelCallbacks = new CopyOnWriteArrayList<Runnable>();

    /** 当前进行中的 LLM 流句柄；未开始流或流已结束时为 {@code null}。 */
    private final AtomicReference<LlmStreamHandle> streamHandle = new AtomicReference<LlmStreamHandle>();

    /** 异步任务句柄，由 {@link #submit} 注入。 */
    private volatile Future<ReActResult> future;

    @Override
    public String getTurnId() {
        return turnId;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        LlmStreamHandle handle = streamHandle.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
        fireCancelCallbacks();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void onCancel(Runnable callback) {
        if (callback == null) {
            return;
        }
        cancelCallbacks.add(callback);
        // 注册时已经取消：立即执行，否则一个刚启动的长调用会直接卡到自己的超时
        if (cancelled.get() && cancelCallbacks.remove(callback)) {
            runQuietly(callback);
        }
    }

    /**
     * 触发全部取消回调，每个回调最多执行一次。
     * <p>
     * 「先摘再执行」是为了让「注册时已取消」与「取消时已注册」这两条路径互斥：两个方向都可能先到，
     * 但 {@code remove} 只有一个能成功，因此回调不会被执行两遍（重复发信号本身无害，
     * 但「同一个动作被保证只做一次」是更好用的契约）。
     */
    private void fireCancelCallbacks() {
        for (Runnable callback : cancelCallbacks) {
            if (cancelCallbacks.remove(callback)) {
                runQuietly(callback);
            }
        }
    }

    /**
     * 执行一个取消回调，异常只记日志。
     * <p>
     * 回调运行在<b>触发取消的那条线程</b>上（{@code -tui} 里就是渲染线程），因此它必须快；
     * 一个回调抛错也不能拦住其余的与流句柄的取消。
     *
     * @param callback 回调
     */
    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.warn("取消回调执行失败", e);
        }
    }

    @Override
    public ReActResult await() {
        Future<ReActResult> current = future;
        if (current == null) {
            throw new JellyfishException("react turn has not been submitted: " + turnId);
        }
        try {
            return current.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JellyfishException("react turn interrupted: " + turnId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof JellyfishException) {
                throw (JellyfishException) cause;
            }
            throw new JellyfishException("react turn failed: " + turnId, cause);
        }
    }

    @Override
    public boolean isDone() {
        Future<ReActResult> current = future;
        return current != null && current.isDone();
    }

    /**
     * 绑定当前 LLM 流句柄。
     *
     * @param handle 流句柄
     */
    void bindHandle(LlmStreamHandle handle) {
        this.streamHandle.set(handle);
    }

    /**
     * 提交异步任务。
     *
     * @param executor 专用执行器
     * @param task     回合任务
     */
    void submit(ExecutorService executor, Callable<ReActResult> task) {
        this.future = executor.submit(task);
    }
}
