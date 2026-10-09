package zcd.jellyfish.core.runtime;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.infra.support.CancellationTokenSource;

import java.util.concurrent.CountDownLatch;

/**
 * 一个 agent run 的句柄：拿身份、等结果、取消。
 * <p>
 * <b>为什么需要它</b>：run 一旦交给调度器就不再在调用线程上同步跑完，调用方需要一个对象来表达
 * 「这次 run 是谁、什么时候结束、怎么取消」。它同时实现 {@link CancellationToken}，
 * 因此可以直接交给 {@code runNested} 当作回合的取消令牌——「取消这次 run」与「掐断这次 run 的
 * LLM 流」是同一件事，不该有两个对象。
 * <p>
 * <b>{@link #await()} 是阻塞的</b>：方案 A 的取舍——调用方（工具线程）本来就要等这次委派结束
 * 才能把结果回灌给模型。等待期间是否让出并发许可由 {@code AgentRuntime.await} 负责，
 * 句柄本身不碰调度。
 * <p>
 * <b>终态恰好一次</b>：{@link #complete(AgentRunResult)} 用 {@code result == null} 判定，
 * 后到的完成请求被忽略——调度器与超时看门狗可能同时想结束同一个 run。
 * <p>
 * 线程安全：结果与超时标记为 volatile，等待用 {@link CountDownLatch}。
 *
 * @author zcd
 */
public final class AgentRunHandle implements CancellationToken {

    /** run 标识。 */
    private final String runId;

    /** 取消令牌本体：取消标志与回调都寄存在它那里。 */
    private final CancellationTokenSource cancellation = new CancellationTokenSource();

    /** 完成闩锁：终态落定时开闸。 */
    private final CountDownLatch done = new CountDownLatch(1);

    /** 终态结果；未完成时为 {@code null}。 */
    private volatile AgentRunResult result;

    /** 是否因墙钟到点被看门狗中断。 */
    private volatile boolean timedOut;

    /**
     * 执行体是否已经返回（此后本 run 只剩收尾）。
     * <p>
     * 与 {@link #timedOut} 不同，它由执行线程在 {@code body.run} 返回后立刻置上，因此
     * {@link #markTimedOut()} 能据此分辨「超时发生在跑的过程中」与「超时晚到、run 其实已经跑完了」。
     */
    private boolean bodyFinished;

    /**
     * 构造句柄，仅供 {@code AgentRuntime} 调用。
     *
     * @param runId run 标识，不可为空白
     */
    AgentRunHandle(String runId) {
        this.runId = runId;
    }

    /**
     * 获取 run 标识。
     *
     * @return run 标识
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 阻塞等待 run 终结并返回结果。
     * <p>
     * 取消不会让本方法提前返回：被取消的 run 会以 {@link AgentRunStatus#CANCELLED} 正常落终态，
     * 调用方因此总能拿到一个明确的结果。
     *
     * @return 终态结果，保证非 {@code null}
     * @throws JellyfishException 等待被中断时抛出
     */
    public AgentRunResult await() {
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JellyfishException("agent run interrupted: " + runId, e);
        }
        return result;
    }

    /**
     * 判断 run 是否已终结。
     *
     * @return 已终结返回 {@code true}
     */
    public boolean isDone() {
        return done.getCount() == 0L;
    }

    /**
     * 取消这次 run。
     * <p>
     * 取消标志会传播到正在跑的 ReAct 回合（本句柄就是它的取消令牌），正在进行的 LLM 流会被掐断。
     */
    public void cancel() {
        cancellation.cancel();
    }

    @Override
    public boolean isCancelled() {
        return cancellation.isCancelled();
    }

    @Override
    public void onCancel(Runnable callback) {
        cancellation.onCancel(callback);
    }

    /**
     * 落终态并开闸。
     * <p>
     * 幂等：第一个到达的完成者胜出，后到的被忽略。
     *
     * @param outcome 终态结果，不可为 {@code null}
     */
    void complete(AgentRunResult outcome) {
        synchronized (this) {
            if (result != null) {
                return;
            }
            result = outcome;
        }
        done.countDown();
    }

    /**
     * 标记执行体已经返回。
     * <p>
     * 由调度器的执行线程在 {@code body.run} 返回之后立即调用；它把「超时」这件事的判定点钉在
     * 「run 还在跑吗」上——看门狗的任务可能排在墙钟到点的那一刻才被调度到，而那时 run 恰好已经
     * 跑完，此时再打超时标记就会把一个<b>完整的结果</b>说成「被墙钟截断」。
     * <p>
     * 与 {@link #markTimedOut()}、{@link #complete(AgentRunResult)} 共用本对象的监视器：
     * 谁先拿到锁谁说话，没有第三种交错。
     */
    synchronized void markBodyFinished() {
        bodyFinished = true;
    }

    /**
     * 标记这次 run 因墙钟到点被中断；run 已经跑完（或在收尾）时不再生效。
     * <p>
     * 由超时看门狗在 {@link #cancel()} 之前调用；调度器据此把「被取消」改标为「截断」。
     *
     * @return 标记生效返回 {@code true}；run 的执行体已返回或已落终态返回 {@code false}
     */
    synchronized boolean markTimedOut() {
        if (bodyFinished || result != null) {
            return false;
        }
        timedOut = true;
        return true;
    }

    /**
     * 判断是否因墙钟到点被中断。
     *
     * @return 到点返回 {@code true}
     */
    boolean isTimedOut() {
        return timedOut;
    }

    @Override
    public String toString() {
        return "AgentRunHandle{run=" + runId + ", done=" + isDone() + '}';
    }
}
