package zcd.jellyfish.core;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.core.tool.CancellationTokenSource;
import zcd.jellyfish.infra.llm.LlmStreamHandle;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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

    /** 回合标识。 */
    private final String turnId = UUID.randomUUID().toString();

    /**
     * 取消令牌本体：取消标志与回调都寄存在它那里。
     * <p>
     * 本类仍实现 {@link CancellationToken}，只是把活转交出去——「回合被取消」只有一份事实，
     * 而输入指令那条路径也需要同一份语义，实现因此只有一处。
     */
    private final CancellationTokenSource cancellation = new CancellationTokenSource();

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
        cancellation.cancel();
        LlmStreamHandle handle = streamHandle.getAndSet(null);
        if (handle != null) {
            handle.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    @Override
    public void onCancel(Runnable callback) {
        cancellation.onCancel(callback);
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
