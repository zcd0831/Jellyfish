package zcd.jellyfish.core;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.support.CancellationTokenSource;

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
 * <b>两种创建方式</b>：{@code submit} 造异步回合（顶层，跑在 {@code react} 池上），
 * {@link #inline(CancellationToken)} 造内联回合（嵌套，由调用线程直接跑完）。两者共用同一套取消语义。
 * <p>
 * <b>它同时是取消令牌本体</b>：不另外造一个对象，是因为「回合被取消」只有一份事实，
 * 两个对象会让「谁先谁后」变成一个需要同步的问题。
 *
 * @author zcd
 */
final class ReActTurnImpl implements ReActTurn, CancellationToken {

    /** 回合标识。 */
    private final String turnId;

    /**
     * 取消令牌本体：取消标志与回调都寄存在它那里。
     * <p>
     * 本类仍实现 {@link CancellationToken}，只是把活转交出去——「回合被取消」只有一份事实，
     * 而输入指令那条路径也需要同一份语义，实现因此只有一处。
     */
    private final CancellationTokenSource cancellation = new CancellationTokenSource();

    /** 当前进行中的 LLM 流句柄；未开始流或流已结束时为 {@code null}。 */
    private final AtomicReference<LlmStreamHandle> streamHandle = new AtomicReference<LlmStreamHandle>();

    /**
     * 异步任务句柄，由 {@link #submit} 注入。
     * <p>
     * 内联回合（见 {@link #inline(CancellationToken)}）不提交任何任务，因此恒为 {@code null}，
     * 对它调 {@link #await()} 会以「尚未提交」失败——调用方本来就不应该等一个同步返回的对象。
     */
    private volatile Future<ReActResult> future;

    /**
     * 构造一个回合（标识由内部生成）。
     */
    ReActTurnImpl() {
        this(UUID.randomUUID().toString());
    }

    /**
     * 构造一个回合，使用外部给定的标识。
     * <p>
     * <b>为什么要允许外部给标识</b>：外壳事件流上的 {@code turnId} 必须在回合启动<b>之前</b>就由
     * 内核确定（订阅者靠它关联整轮事件），而句柄是 {@code chat} 返回之后才有的。
     * 若内部再生成一个，同一条回合就会有两个标识，而它们在任何地方都不一致。
     *
     * @param turnId 回合标识，不可为空白
     */
    ReActTurnImpl(String turnId) {
        if (turnId == null || turnId.trim().isEmpty()) {
            throw new JellyfishException("turnId must not be blank");
        }
        this.turnId = turnId;
    }

    /**
     * 构造一个内联回合：不提交执行器，由调用方在自己的线程上跑循环。
     * <p>
     * <b>为什么需要它</b>：子代理的嵌套回合必须在调用线程上同步跑完（见
     * {@code ReActLooper.runNested}），但它同样需要「取消」与「掐断进行中的流」两件事——
     * 那正好就是本类已经拥有的一切。新造一个同形状的对象只会让取消语义多一份实现。
     * <p>
     * <b>父取消直接接管本回合</b>：把父令牌的回调接到 {@link #cancel()} 上，因此用户按下 Esc 之后，
     * 正在跑的嵌套回合与父回合同时收敛。父令牌已经取消时，注册会立即执行回调，
     * 于是嵌套回合在第一个检查点上就会退出。
     *
     * @param parentCancellation 父回合的取消令牌，可为 {@code null}（表示本次嵌套不接受外部取消）
     * @return 内联回合句柄
     */
    static ReActTurnImpl inline(CancellationToken parentCancellation) {
        ReActTurnImpl turn = new ReActTurnImpl();
        if (parentCancellation != null) {
            parentCancellation.onCancel(turn::cancel);
        }
        return turn;
    }

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
