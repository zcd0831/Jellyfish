package zcd.jellyfish.core;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.llm.LlmStreamHandle;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ReActTurn} 的默认实现：包级可见，只由 {@link ReActLooper} 创建。
 * <p>
 * 持有三样东西：异步任务的 {@link Future}、取消标志、当前进行中的 LLM 流句柄。
 * 取消是协作式的——置标志只能阻止「下一轮 / 下一个工具」，正在进行的 LLM 流由句柄直接掐断。
 *
 * @author zcd
 */
final class ReActTurnImpl implements ReActTurn {

    /** 回合标识。 */
    private final String turnId = UUID.randomUUID().toString();

    /** 取消标志。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

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
     * 判断是否已被取消。
     *
     * @return 已置取消标志返回 {@code true}
     */
    boolean isCancelled() {
        return cancelled.get();
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
