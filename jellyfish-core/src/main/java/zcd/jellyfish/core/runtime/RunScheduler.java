package zcd.jellyfish.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * run 调度器：把 run 放到独立于 {@code react} 池的执行资源上，并守住全局并发上限与墙钟上限。
 * <p>
 * <b>为什么必须有独立执行资源</b>：父回合在等子代理时会阻塞在自己的线程上。若把 run 排回 {@code react}
 * 池，几条并发父回合就能占满池并互相等死（旧设计用「内联执行」回避的正是这件事）。因此 run 跑在自己的
 * {@code agent-run} 池上，与 {@code react} 池不共享队列。
 * <p>
 * <b>线程与并发许可是两件事</b>：池只负责「提供线程」，真正的并发控制交给许可信号量。理由同样是等待——
 * 一个 run 等孩子时会占着一条线程，但它<b>不该占着并发许可</b>；两者若合一，深度大于 1 时就会被
 * 「正在等孩子的父」占满而自锁死。等待窗口里让出许可由 {@code AgentRuntime.await} 配合
 * {@link RunPermit} 完成。
 * <p>
 * <b>池为什么用 cached 而不是固定大小</b>：固定大小会把「等待中的父」也算进并发度。这里用
 * {@code corePoolSize = 0} 的按需线程池，上界取 {@code maxConcurrentRuns × (maxDepth + 1)}——
 * 每一层最多这么多 run 在飞，再加父层，就是「活跃 + 等待中的父」的上界。提交被拒（池满）时 run 以
 * 失败落终态并回报原因，不静默丢弃。
 * <p>
 * <b>墙钟看门狗为什么独立于执行池</b>：到点要动用的是「取消」而不是「再跑一个任务」；把定时器放在
 * 执行池里，池一旦被在途 run 占满，超时处理本身就会被饿死，于是最需要它的时刻它反而不工作。
 * <p>
 * <b>上下文怎么传给子线程</b>：调度器在执行线程上装载该 run 的 {@link RunContext}（共享父的
 * {@link RunTree}、带上自己的 runId 与许可），执行完清掉。父上下文里的两个值（树引用、进入深度）
 * 在提交时<b>同步读取快照</b>，因为父线程随后还会改动它自己的上下文对象。
 * <p>
 * 线程安全：池、信号量本身线程安全；每个 worker 只碰自己的上下文与句柄。
 *
 * @author zcd
 */
@Singleton
public final class RunScheduler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(RunScheduler.class);

    /** 全局并发许可。 */
    private final Semaphore permits;

    /** 执行 run 的专用线程池。 */
    private final ThreadPoolExecutor executor;

    /** 墙钟看门狗：到点取消在途 run。 */
    private final ScheduledExecutorService watchdog;

    /** 上下文持有者：把 run 的上下文装载到执行线程上。 */
    private final RunContextHolder contexts;

    /** run 登记表：落终态与用量。 */
    private final RunRegistry registry;

    /** 单个 run 的墙钟上限（毫秒）；{@code <= 0} 表示不看门狗。 */
    private final long runTimeoutMillis;

    /**
     * 构造调度器。
     * <p>
     * 池大小、许可数与墙钟上限在构造时按当前配置确定；配置热更新不会重建它们（改动需重启），
     * 这是刻意的——重建一个正在跑任务的池意味着要么丢弃在途 run，要么放弃旧池，
     * 两者都比「下次重启生效」更糟。
     *
     * @param contexts      上下文持有者，不可为 {@code null}
     * @param registry      run 登记表，不可为 {@code null}
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public RunScheduler(RunContextHolder contexts, RunRegistry registry, RuntimeConfig runtimeConfig) {
        this.contexts = contexts;
        this.registry = registry;
        SubAgentSettings settings = runtimeConfig.getSubAgentSettings();
        int concurrent = Math.max(1, settings.getMaxConcurrentRuns());
        int poolMax = concurrent * (Math.max(0, settings.getMaxDepth()) + 1);
        this.permits = new Semaphore(concurrent);
        this.executor = new ThreadPoolExecutor(0, poolMax, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), threadFactory("agent-run-"), new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.runTimeoutMillis = settings.getRunTimeoutMillis();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(threadFactory("agent-run-watchdog-"));
    }

    /**
     * 提交一个 run 供调度执行。
     * <p>
     * 本方法不阻塞：它只负责把任务交给线程池并登记墙钟看门狗。拿不到并发许可时 run 会在执行线程上
     * 先等许可（等待中的 run 不占许可，见类注释），池满则当场以失败落终态。
     *
     * @param runId       run 标识
     * @param body        执行体
     * @param tree        该 run 所属 run 树共享的账本
     * @param parentDepth 父路径当前的委派深度（提交时同步读取的快照）
     * @param rootRunId   该 run 所属 run 树的根标识
     * @param handle      run 句柄
     */
    void submit(String runId, AgentRunBody body, RunTree tree, int parentDepth, String rootRunId,
                AgentRunHandle handle) {
        ScheduledFuture<?> timeout = scheduleTimeout(runId, handle);
        try {
            executor.execute(() -> runTask(runId, body, tree, parentDepth, rootRunId, handle, timeout));
        } catch (RejectedExecutionException e) {
            cancelTimeout(timeout);
            LOG.warn("run 提交被拒（agent-run 线程池已满）: runId={}", runId);
            AgentRunResult result = AgentRunResult.failed(
                    "agent-run 线程池已满，无法派生更多子代理（可稍后重试或调小 subAgent.maxConcurrentRuns）");
            registry.finish(runId, result.getStatus(), result.getRounds(), result.getUsage());
            handle.complete(result);
        }
    }

    /**
     * 登记墙钟看门狗：到点把 run 标记为超时并取消（取消会掐断它正在进行的 LLM 流）。
     *
     * @param runId  run 标识
     * @param handle run 句柄
     * @return 定时句柄；不看门狗时为 {@code null}
     */
    private ScheduledFuture<?> scheduleTimeout(String runId, AgentRunHandle handle) {
        if (runTimeoutMillis <= 0L) {
            return null;
        }
        return watchdog.schedule(() -> {
            LOG.warn("子代理触达墙钟上限，取消: runId={} timeoutMillis={}", runId, runTimeoutMillis);
            handle.markTimedOut();
            handle.cancel();
        }, runTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 撤销墙钟看门狗；已触发或不存在时无操作。
     *
     * @param timeout 定时句柄，可为 {@code null}
     */
    private static void cancelTimeout(ScheduledFuture<?> timeout) {
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    /**
     * 执行线程上的任务体：取许可 → 装载上下文 → 跑执行体 → 落终态 → 还许可。
     *
     * @param runId       run 标识
     * @param body        执行体
     * @param tree        所属 run 树共享的账本
     * @param parentDepth 父路径深度快照
     * @param rootRunId   树根标识
     * @param handle      run 句柄
     * @param timeout     墙钟看门狗句柄，可为 {@code null}
     */
    private void runTask(String runId, AgentRunBody body, RunTree tree, int parentDepth, String rootRunId,
                         AgentRunHandle handle, ScheduledFuture<?> timeout) {
        permits.acquireUninterruptibly();
        RunPermit permit = new RunPermit(permits);
        contexts.set(new RunContext(tree, parentDepth, runId, rootRunId, permit));
        AgentRunResult result;
        try {
            result = body.run(handle);
        } catch (RuntimeException e) {
            LOG.warn("agent run 执行体抛错: runId={}", runId, e);
            result = AgentRunResult.failed(messageOf(e));
        } finally {
            contexts.close();
            permit.suspend();
            cancelTimeout(timeout);
            // 收尾时清理遗留子树：父 run 失败/被取消时，已派生但还没回收的后代必须一并取消，
            // 否则它们会变成没人读结果的孤儿 run，继续占着并发许可与 token
            registry.cancelDescendants(runId);
        }
        AgentRunResult terminal = normalize(result);
        if (handle.isTimedOut()) {
            // 看门狗先取消、执行体随之以 CANCELLED 收敛；这里把它如实改标为「截断」
            terminal = terminal.asTruncated("已达到单个子代理的墙钟上限（" + runTimeoutMillis
                    + " ms）：如需继续请调大 subAgent.runTimeoutMillis。");
        }
        registry.finish(runId, terminal.getStatus(), terminal.getRounds(), terminal.getUsage());
        handle.complete(terminal);
    }

    /**
     * 把执行体返回的结果归一到「一定是终态」。
     * <p>
     * 执行体返回 {@code null} 或非终态属于编程错误，但收尾处不能因此把 run 卡在非终态上，
     * 所以统一落成失败。
     *
     * @param result 执行体返回的结果，可为 {@code null}
     * @return 终态结果，保证非 {@code null}
     */
    private static AgentRunResult normalize(AgentRunResult result) {
        if (result == null) {
            return AgentRunResult.failed("agent run 执行体返回空结果");
        }
        if (!result.getStatus().isTerminal()) {
            return AgentRunResult.failed("agent run 执行体返回了非终态结果：" + result.getStatus());
        }
        return result;
    }

    /**
     * 取异常的可用消息。
     *
     * @param throwable 异常，不可为 {@code null}
     * @return 消息文本；消息为空时退化为类名
     */
    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * 构造守护线程工厂：线程名带前缀与自增序号，便于线程转储时辨认。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂，保证非 {@code null}
     */
    private static ThreadFactory threadFactory(String prefix) {
        return new ThreadFactory() {

            /** 自增序号。 */
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
