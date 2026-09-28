package zcd.jellyfish.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.CancellationToken;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可取消的令牌本体：发起方创建它、交给可能长时间阻塞的工具，之后由任意线程（界面按 Esc）触发取消。
 * <p>
 * <b>为什么需要一个内核侧实现</b>：{@link CancellationToken} 只是一个接口，而取消的发起方有两类——
 * ReAct 回合与输入指令。前者原本自带一份实现（{@code ReActTurnImpl}），后者也需要一份，
 * 于是抽成共享实现，两边都委托它。取消回调的「恰好执行一次」语义因此只有一处需要维护。
 * <p>
 * <b>不是线程安全的替代品</b>：本类自身线程安全（标志是原子的、回调表是写时复制的），
 * 但回调运行在触发取消的那条线程上，因此回调本身必须快——见 {@link CancellationToken} 的约束。
 *
 * @author zcd
 */
public final class CancellationTokenSource implements CancellationToken {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CancellationTokenSource.class);

    /** 取消标志。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 取消回调，按注册顺序执行。
     * <p>
     * {@code CopyOnWriteArrayList} 是因为注册与触发可能在不同线程上（工具线程注册、界面线程触发），
     * 而回调数量极少（一次调用最多一个），写时复制的代价可以忽略。
     */
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<Runnable>();

    /**
     * 触发取消：置标志并执行全部已注册回调，幂等。
     * <p>
     * 回调「先摘再执行」，让「注册时已取消」与「取消时已注册」两条路径互斥——两个方向都可能先到，
     * 但 {@code remove} 只有一个能成功，因此回调恰好被执行一次。重复发信号本身无害，
     * 但「同一个动作被保证只做一次」是更好用的契约。
     */
    public void cancel() {
        cancelled.set(true);
        for (Runnable callback : callbacks) {
            if (callbacks.remove(callback)) {
                runQuietly(callback);
            }
        }
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
        callbacks.add(callback);
        // 注册时已经取消：立即执行，否则一个刚启动的长调用会直接卡到自己的超时
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
    }

    /**
     * 执行一个取消回调，异常只记日志。
     * <p>
     * 回调运行在<b>触发取消的那条线程</b>上（{@code -tui} 里就是渲染线程），因此它必须快；
     * 一个回调抛错也不能拦住其余的取消动作。
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
}
