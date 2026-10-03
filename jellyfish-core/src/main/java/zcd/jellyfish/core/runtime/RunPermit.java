package zcd.jellyfish.core.runtime;

import java.util.concurrent.Semaphore;

/**
 * 一次 run 持有的并发许可：允许在执行期间「让出」再「取回」。
 * <p>
 * <b>为什么需要「让出」</b>：方案 A 下父 run 等孩子时会阻塞在自己的线程上。若它一直占着
 * 并发许可，孩子就可能永远排不上号（全局许可被一群「正在等孩子的父」占满）。因此在
 * {@code AgentRuntime.await} 的等待窗口里先 {@link #suspend()}、等完再 {@link #resume()}，
 * 让等待中的 run 不占运行槽位。
 * <p>
 * <b>为什么最终释放走同一个 {@link #suspend()}</b>：run 结束时也是「不再需要这个许可」，
 * 与等待时的语义一致，因此不另设 release 方法，避免两条路径对 {@code held} 的维护产生分歧。
 * <p>
 * 线程安全：读写 {@code held} 与许可的同一步操作都在实例锁内。
 *
 * @author zcd
 */
final class RunPermit {

    /** 共享的许可信号量。 */
    private final Semaphore permits;

    /** 当前是否持有许可。 */
    private boolean held = true;

    /**
     * 构造许可，构造时即视为已持有。
     *
     * @param permits 已成功获取过一次的许可信号量，不可为 {@code null}
     */
    RunPermit(Semaphore permits) {
        this.permits = permits;
    }

    /**
     * 让出许可：已让出时重复调用是幂等的。
     */
    synchronized void suspend() {
        if (held) {
            held = false;
            permits.release();
        }
    }

    /**
     * 取回许可：已持有时重复调用是幂等的；拿不到时阻塞等待。
     */
    synchronized void resume() {
        if (!held) {
            permits.acquireUninterruptibly();
            held = true;
        }
    }
}
