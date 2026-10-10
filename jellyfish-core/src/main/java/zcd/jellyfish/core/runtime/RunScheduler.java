package zcd.jellyfish.core.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <b>池为什么按上界固定、而不是 {@code corePoolSize = 0} 的按需池</b>：线程数与并发许可是两件事——
 * 「活跃的 run」最多 {@code maxConcurrentRuns} 个，但「正在等孩子的父 run」也占着线程，所以线程数
 * 上界取 {@code maxConcurrentRuns × (maxDepth + 1)}。这个上界<b>一次建满并常驻</b>：按需扩容在有队列时
 * 根本不会发生——JDK 只在<b>队列满</b>时才把线程数从 core 扩到 max，于是「队列里还有空位」反而会让
 * 并发退化成一两条线程，比没有队列更慢。常驻的是守护线程，空闲时阻塞在队列上，代价是几条线程栈。
 * <p>
 * <b>配置什么时候读</b>：{@link RuntimeConfig} 的快照要到装配之后、{@code AgentHarness.bootstrap()}
 * 里的 {@code refresh()} 才装上，而本类由装配根在那之前构造——<b>构造期读配置只会读到缺省值</b>。
 * 那是一种没有任何症状的失效：用户把 {@code runTimeoutMillis} 写成 20 分钟，现场表现仍然是一律按
 * 300 秒掐断，而「配置里明明写着」这句话会把人引向完全错误的方向。因此：墙钟在<b>每次起看门狗时</b>
 * 按当时的配置读，并发 / 等待区 / 池上界在<b>第一次提交</b>时按当时的配置建一次。
 * <p>
 * <b>队列为什么有界</b>：并发是硬上限（它守的是模型调用配额与机器负载），但「这一批派得比并发多」
 * 不该等于「多出来的当场失败」——一次扇出 8 个而并发是 3 是常见情形。于是超出的 run 进队列等许可，
 * 积压上限由 {@code maxQueuedRuns} 给定；只有「线程满 + 队列也满」才当场失败。无界队列会把「编排跑飞」
 * 放大成内存问题，因此这个上界不允许配 0。
 * <p>
 * <b>墙钟为什么从「开始执行」起算</b>：{@code runTimeoutMillis} 约束的是「这个 run 跑了多久」，而排队
 * 等许可的时间不是它跑的时间——从提交时刻起算会让「扇得多」直接变成「排在后面的被判超时」，那正是
 * 队列要解决的那个问题。代价是排队本身不受<b>超时</b>约束；但<b>取消照常生效</b>：等许可的循环每
 * {@value #PERMIT_POLL_MILLIS} 毫秒查一次取消标记，被取消的 run 当场按「已取消」收尾（见
 * {@link #acquirePermit(Execution, AgentRunHandle)}）。
 * <p>
 * <b>墙钟看门狗为什么独立于执行池</b>：到点要动用的是「取消」而不是「再跑一个任务」；把定时器放在
 * 执行池里，池一旦被在途 run 占满，超时处理本身就会被饿死，于是最需要它的时刻它反而不工作。
 * <p>
 * <b>上下文怎么传给子线程</b>：调度器在执行线程上装载该 run 的 {@link RunContext}（共享父的
 * {@link RunTree}、带上自己的 runId 与许可），执行完清掉。父上下文里的两个值（树引用、进入深度）
 * 在提交时<b>同步读取快照</b>，因为父线程随后还会改动它自己的上下文对象。
 * <p>
 * <b>关闭是一个显式动作</b>（{@link #close()}）：「不再派新的 run」只是它的一半，另一半是让<b>已经在
 * 途与仍在排队的</b> run 都走到终态——{@code shutdownNow()} 会把排队任务丢掉，那些 run 从此没有人
 * 来落终态，等它们的父回合会永久挂住。顺序是立旗 → 取消 → 丢队列 → 有界等待 → 兜底收尾；
 * 兜底如实落「已取消」（它们此前确实都被取消过），而不是编一个失败原因。
 * <p>
 * 线程安全：池、信号量本身线程安全；每个 worker 只碰自己的上下文与句柄。
 *
 * @author zcd
 */
@Singleton
public final class RunScheduler implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(RunScheduler.class);

    /** 关闭时等在途 run 收敛的上限（毫秒）。超过就如实收尾并记 WARN，不无限期拖住关闭。 */
    private static final long CLOSE_WAIT_MILLIS = 2000L;

    /** 关闭等待的轮询间隔（毫秒）。 */
    private static final long CLOSE_WAIT_POLL_MILLIS = 10L;

    /**
     * 等并发许可的轮询步长（毫秒）。
     * <p>
     * 取 50：它只影响「本来就要排队」的那些 run 的开始时刻（最多晚 50ms），而不排队的 run 走
     * {@link Semaphore#tryAcquire()} 快路径、完全不受影响；换来的是取消能被及时看见。
     */
    private static final long PERMIT_POLL_MILLIS = 50L;

    /** 关闭之后对「还在跑」的 run 的收尾原因；也用于拒绝关闭期新增的委派。 */
    private static final String SHUTDOWN_REASON = "内核正在关闭，不再接受新的子代理委派";

    /** 运行时配置门面：执行资源与墙钟都按「用到的那一刻」的配置读，见类注释。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 执行资源（许可 / 线程池 / 队列容量）：第一次提交时按当时的配置建一次，此后不再变。
     * <p>
     * {@code volatile} + 双重检查：读路径在每次提交上，必须便宜；而它一旦建好就再也不会被替换。
     */
    private volatile Execution execution;

    /** 墙钟看门狗：到点取消在途 run。它不依赖任何配置，因此在构造期就建好。 */
    private final ScheduledExecutorService watchdog;

    /**
     * 在途与排队的 run 句柄：关闭时逐个取消，并把被丢弃的那些收尾。
     * <p>
     * <b>为什么调度器自己要记一份</b>：登记表（{@code RunRegistry}）里只有「状态」，没有「等它的人是谁」；
     * 而关闭时要把「排队中被丢弃、执行体从未开跑」的 run 也落成终态，否则等它的父回合永远等不到结果。
     * 条目在 {@link #finishNow} 里摘掉，因此它只覆盖「还没落终态」的那些。
     */
    private final Map<String, AgentRunHandle> pending =
            new ConcurrentHashMap<String, AgentRunHandle>();

    /** 是否已关闭；关闭之后不再接受新的委派。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 上下文持有者：把 run 的上下文装载到执行线程上。 */
    private final RunContextHolder contexts;

    /** run 登记表：落终态与用量。 */
    private final RunRegistry registry;

    /** run 事件总线：对外广播生命周期事件。 */
    private final RunEventBus events;

    /**
     * 构造调度器。
     * <p>
     * <b>构造期不读任何配置</b>：此刻 {@link RuntimeConfig} 还没有快照（它由 {@code refresh()} 装载，
     * 而那一步发生在装配之后），读了只会拿到缺省值。执行资源因此推迟到第一次提交时按当时的配置建一次，
     * 此后不重建——重建一个正在跑任务的池意味着要么丢弃在途 run，要么放弃旧池，
     * 两者都比「改并发 / 等待区需重启」更糟。
     *
     * @param contexts      上下文持有者，不可为 {@code null}
     * @param registry      run 登记表，不可为 {@code null}
     * @param events        run 事件总线，不可为 {@code null}
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public RunScheduler(RunContextHolder contexts, RunRegistry registry, RunEventBus events,
                        RuntimeConfig runtimeConfig) {
        this.contexts = contexts;
        this.registry = registry;
        this.events = events;
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.watchdog = Executors.newSingleThreadScheduledExecutor(threadFactory("agent-run-watchdog-"));
    }

    /**
     * 取（必要时创建）执行资源。
     * <p>
     * <b>为什么推迟到第一次提交</b>：见类注释——构造期读配置只会读到缺省值。
     * 代价是「并发 / 等待区」在首次提交那一刻定型，之后即使 {@code /reload} 也不变（语义仍是「改它们需重启」）；
     * 墙钟不在此列，它每次起看门狗时读。
     *
     * @return 执行资源，保证非 {@code null}
     */
    private Execution execution() {
        Execution current = execution;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (execution == null) {
                execution = openExecution();
            }
            return execution;
        }
    }

    /**
     * 按当前配置建立执行资源：许可数、池上界与等待区容量。
     *
     * @return 执行资源，保证非 {@code null}
     */
    private Execution openExecution() {
        SubAgentSettings settings = runtimeConfig.getSubAgentSettings();
        int concurrent = Math.max(1, settings.getMaxConcurrentRuns());
        int poolSize = concurrent * (Math.max(0, settings.getMaxDepth()) + 1);
        int capacity = Math.max(1, settings.getMaxQueuedRuns());
        LOG.debug("建立 run 执行资源: concurrent={} poolSize={} queueCapacity={}", concurrent, poolSize,
                capacity);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(capacity), threadFactory("agent-run-"),
                new ThreadPoolExecutor.AbortPolicy());
        return new Execution(new Semaphore(concurrent), executor, capacity);
    }

    /**
     * 取本次 run 的墙钟上限。
     * <p>
     * <b>每次读，不缓存</b>：它与执行资源不同——看门狗是「每个 run 各自一个定时器」，没有池那种
     * 「重建就得动在途任务」的约束，因此改配置之后下一个 run 就按新值计时。
     *
     * @return 墙钟上限（毫秒）；{@code <= 0} 表示不看门狗
     */
    private long watchdogMillis() {
        return runtimeConfig.getSubAgentSettings().getRunTimeoutMillis();
    }

    /**
     * 提交一个 run 供调度执行。
     * <p>
     * 本方法不阻塞：它只负责把任务交给线程池。线程满时任务进队列等一个空闲线程，拿到线程后若并发
     * 许可已被占满则在线程上等许可（等待中的 run 不占许可，见类注释）——只有「线程满且队列也满」才
     * 当场以失败落终态。
     * <p>
     * <b>关闭之后一律拒绝，并如实说明是「内核在关」</b>：那一刻线程池已经关了，再走「排队已满」那条
     * 分支会把两件不同的事说成同一件（用户会去调 {@code maxQueuedRuns}，而真正该做的是别再派）。
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
        if (closed.get()) {
            finishNow(runId, AgentRunResult.failed(SHUTDOWN_REASON), handle);
            return;
        }
        pending.put(runId, handle);
        Execution target = execution();
        try {
            target.executor.execute(
                    () -> runTask(target, runId, body, tree, parentDepth, rootRunId, handle));
        } catch (RejectedExecutionException e) {
            LOG.warn("run 提交被拒: runId={} closed={} queueCapacity={}", runId, closed.get(),
                    target.queueCapacity);
            finishNow(runId, AgentRunResult.failed(rejectionReason(target)), handle);
        }
    }

    /**
     * 取「这次提交为什么被拒」的如实说法。
     * <p>
     * 关闭与队列满是两种情形：前者不该让用户去调配置键，后者应当——而两者都会表现为执行器的
     * {@code RejectedExecutionException}（关停之后的提交也抛它），因此判据取关闭标志。
     *
     * @param target 建立时定型的执行资源，用来如实说出「等待区有多大」
     * @return 拒绝原因，保证非 {@code null}
     */
    private String rejectionReason(Execution target) {
        if (closed.get()) {
            return SHUTDOWN_REASON;
        }
        return "子代理排队已满（subAgent.maxQueuedRuns=" + target.queueCapacity
                + "）：在途与排队的 run 已占满等待区，可稍后重试或调大 subAgent.maxQueuedRuns";
    }

    /**
     * 关闭调度器：不再接受新的委派，取消并收尾在途与排队的 run，然后关掉池与看门狗。幂等。
     * <p>
     * <b>为什么必须显式收尾，而不是只关池</b>：{@code shutdownNow()} 会把<b>排队中等许可</b>的任务丢掉，
     * 那些 run 的执行体从此不会跑，也就没有人来给它们落终态——而等待方（父回合或面板）拿不到结果的
     * 表现是永久挂住，不是一条错误。因此顺序是「立旗 → 取消在途 → 丢队列 → 有界等待 → 兜底落终态」。
     * <p>
     * <b>兜底那条为什么是「已取消」而不是「失败」</b>：它们此前都被 {@link AgentRunHandle#cancel()} 取消过，
     * 说「已取消」符合事实；说「失败」会把「内核关了」和「它真的错了」混成同一件事。剩下的那些
     * （取消没赶上、仍在长工具里）会在日志里被点名，代价是它的真实结果不会再被任何人使用——
     * 这一点无从补救，因为读取它的人自己也在关闭中。
     * <p>
     * <b>顺序为什么是这个顺序</b>：先立旗（挡掉新委派），再取消（让在跑的尽快收敛），
     * 再丢队列（把还没开跑的摘出来），然后才等待——等待的对象因此恰好剩下「已经开始跑的那些」。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.debug("关闭 run 调度器: pending={} execution={}", pending.size(), execution != null);
        for (AgentRunHandle handle : pending.values()) {
            handle.cancel();
        }
        // 从未提交过任何 run 时执行资源还没建：没有池可关、也没有许可要放，兜底收尾照做
        Execution current = execution;
        if (current != null) {
            current.executor.shutdownNow();
        }
        awaitPending();
        finishRemaining();
        watchdog.shutdownNow();
    }

    /**
     * 有界等待在途 run 收敛：等到 {@code pending} 空或超时。
     * <p>
     * <b>为什么是轮询而不是 join 某个线程</b>：run 与线程不是一对一——它可能正在等并发许可、
     * 可能已经完成但还没被摘掉，没有一个可 join 的对象。判据因此取「它还留在 pending 里吗」，
     * 而条目正是在 {@link #finishNow}（终态）里摘掉的。
     */
    private void awaitPending() {
        long deadline = System.currentTimeMillis() + CLOSE_WAIT_MILLIS;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(CLOSE_WAIT_POLL_MILLIS);
            } catch (InterruptedException e) {
                // 有人在催关闭：不再等，直接走兜底收尾，但把中断标记还回去让调用方知道
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 兜底收尾：把还没落终态的 run 就地落成终态，让等它的人拿得到结果。
     */
    private void finishRemaining() {
        for (Map.Entry<String, AgentRunHandle> entry : pending.entrySet()) {
            AgentRunHandle handle = entry.getValue();
            if (handle.isDone()) {
                continue;
            }
            LOG.warn("内核关闭：run 未在 {} ms 内收敛，就地按「已取消」收尾: runId={}", CLOSE_WAIT_MILLIS,
                    entry.getKey());
            finishNow(entry.getKey(), AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null,
                    "内核正在关闭，这次委派的结果不会再被使用"), handle);
        }
    }

    /**
     * 落终态、广播并开闸：三条终结路径（正常跑完、排队被拒、排队中被取消）共用同一个收尾顺序。
     * <p>
     * <b>先广播终态再开闸</b>：订阅者应当在等待方恢复之前就看到 FINISHED，否则「await 返回后事件
     * 还没到」会变成一条难复现的竞态。
     *
     * @param runId  run 标识
     * @param result 终态结果，不可为 {@code null}
     * @param handle run 句柄，不可为 {@code null}
     */
    private void finishNow(String runId, AgentRunResult result, AgentRunHandle handle) {
        // 先从「还没落终态」的清单里摘掉：它正是关闭时「还有谁在等结果」的判据
        pending.remove(runId);
        registry.finish(runId, result.getStatus(), result.getRounds(), result.getUsage());
        publishFinished(runId);
        handle.complete(result);
    }

    /**
     * 登记墙钟看门狗：到点把 run 标记为超时并取消（取消会掐断它正在进行的 LLM 流）。
     *
     * @param runId         run 标识
     * @param handle        run 句柄
     * @param timeoutMillis 本次 run 的墙钟上限（毫秒），由调用方按当时的配置取好
     * @return 定时句柄；不看门狗时为 {@code null}
     */
    private ScheduledFuture<?> scheduleTimeout(String runId, AgentRunHandle handle, long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            return null;
        }
        return watchdog.schedule(() -> {
            if (handle.markTimedOut()) {
                LOG.warn("子代理触达墙钟上限，取消: runId={} timeoutMillis={}", runId, timeoutMillis);
                handle.cancel();
            } else {
                // 看门狗排在墙钟到点的那一刻才被调度到，而 run 恰好已经跑完收尾了。
                // 不取消、也不改标：它没被墙钟打断，把完整结果说成「截断」会让模型去调配置
                LOG.debug("看门狗晚到，run 已经跑完，超时标记不生效: runId={}", runId);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
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
     * 取一份并发许可，期间可被取消打断。
     * <p>
     * <b>为什么不直接用 {@code acquireUninterruptibly()}</b>：那个调用既不理会中断，也没有任何地方会在
     * 取消时唤醒它——于是一个「还在等待区里排队」的 run 被取消之后<b>不会收敛</b>：它的句柄一直不落终态，
     * 等它的父回合（以及驱动它的插件）就永远等不到结果，界面上表现为「按了中断，什么都没发生」。
     * 这里改成「先试一次、失败再小步轮询」，每步之间查一次取消标记。
     * <p>
     * <b>拿到之后还要复查一次</b>：取消可能正好落在「试到了」与「提交执行」之间，而那一次漏查意味着
     * 这个 run 会照常开跑，后面所有取消检查都要等到下一个检查点。多查一次的成本是一次 volatile 读。
     *
     * @param target 执行资源
     * @param handle run 句柄
     * @return 拿到许可且未发现取消返回 {@code true}；等许可期间被取消或被中断返回 {@code false}
     */
    private static boolean acquirePermit(Execution target, AgentRunHandle handle) {
        while (true) {
            if (handle.isCancelled()) {
                return false;
            }
            try {
                if (!target.permits.tryAcquire(PERMIT_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    continue;
                }
            } catch (InterruptedException e) {
                // 关停会中断等待中的 worker：退出等待，由调用方按「已取消」收尾——close() 一定先取消过
                // 这些 run，因此那句话是如实的。中断标记还回去，让「这个线程被催过」这件事保持可见
                Thread.currentThread().interrupt();
                return false;
            }
            if (handle.isCancelled()) {
                // 拿到许可的同一刻被取消：还回去，不留一份没人用的许可
                target.permits.release();
                return false;
            }
            return true;
        }
    }

    /**
     * 执行线程上的任务体：查取消 → 取许可 → 装载上下文 → 起墙钟 → 跑执行体 → 落终态 → 还许可。
     * <p>
     * <b>排到队尾也得先查一次取消</b>：run 可能在队列里等了很久，而等它的那个回合早就结束了
     * （用户中止回合、或父 run 失败触发级联取消）。那时再跑一轮既没人要结果，又要占一份许可与 token。
     *
     * @param target     建立时定型的执行资源（许可从这里取）
     * @param runId       run 标识
     * @param body        执行体
     * @param tree        所属 run 树共享的账本
     * @param parentDepth 父路径深度快照
     * @param rootRunId   树根标识
     * @param handle      run 句柄
     */
    private void runTask(Execution target, String runId, AgentRunBody body, RunTree tree, int parentDepth,
                         String rootRunId, AgentRunHandle handle) {
        if (handle.isCancelled()) {
            LOG.debug("run 在排队期间被取消，跳过执行: runId={}", runId);
            finishNow(runId, AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null), handle);
            return;
        }
        if (!acquirePermit(target, handle)) {
            LOG.debug("run 在等并发许可期间被取消，跳过执行: runId={}", runId);
            finishNow(runId, AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null), handle);
            return;
        }
        RunPermit permit = new RunPermit(target.permits);
        contexts.set(new RunContext(tree, parentDepth, runId, rootRunId, permit));
        registry.markRunning(runId);
        publishStarted(runId);
        // 墙钟从拿到许可、真正开跑的这一刻起算：排队等许可的时间不是这个 run 跑的时间。
        // 上限按「这一刻」的配置取：它不像池那样重建就得动在途任务，改完下一个 run 就按新值计时
        long timeoutMillis = watchdogMillis();
        ScheduledFuture<?> timeout = scheduleTimeout(runId, handle, timeoutMillis);
        AgentRunResult result;
        try {
            result = body.run(handle);
        } catch (RuntimeException e) {
            LOG.warn("agent run 执行体抛错: runId={}", runId, e);
            result = AgentRunResult.failed(messageOf(e));
        } finally {
            // 第一件事就是宣告「执行体已经返回」：从这一刻起，看门狗再触发也不再算超时
            // （它的判据是「run 还在跑吗」，而不是「定时器有没有响过」）
            handle.markBodyFinished();
            contexts.close();
            permit.suspend();
            cancelTimeout(timeout);
            // 收尾时清理遗留子树：父 run 失败/被取消时，已派生但还没回收的后代必须一并取消，
            // 否则它们会变成没人读结果的孤儿 run，继续占着并发许可与 token
            registry.cancelDescendants(runId);
        }
        AgentRunResult terminal = normalize(result);
        if (handle.isTimedOut()) {
            // 看门狗先取消、执行体随之以 CANCELLED 收敛；这里把它如实改标为「截断」。
            // 标记只在「run 还没跑完」时打得进来（见 AgentRunHandle#markBodyFinished），
            // 因此一个正常跑完的 run 不会因为看门狗晚到而被说成截断
            terminal = terminal.asTruncated("已达到单个子代理的墙钟上限（" + timeoutMillis
                    + " ms）：如需继续请调大 subAgent.runTimeoutMillis。");
        }
        finishNow(runId, terminal, handle);
    }

    /**
     * 广播「run 开始执行」（状态已置为运行中）。
     *
     * @param runId run 标识
     */
    private void publishStarted(String runId) {
        registry.snapshot(runId).ifPresent(snapshot -> events.publish(AgentRunEvent.started(snapshot)));
    }

    /**
     * 广播「run 到达终态」。
     *
     * @param runId run 标识
     */
    private void publishFinished(String runId) {
        registry.snapshot(runId).ifPresent(snapshot -> events.publish(AgentRunEvent.finished(snapshot)));
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
     * 执行资源：并发许可、专用线程池与等待区容量。
     * <p>
     * <b>为什么打成一个包</b>：这三样由同一份配置（{@code maxConcurrentRuns} / {@code maxDepth} /
     * {@code maxQueuedRuns}）一起决定，且必须在同一时刻定型——许可数与池上界若来自两份不同的配置快照，
     * 就会出现「许可比线程多」（一部分 run 永远等不到线程）或反过来（线程空转）这种自相矛盾的装配。
     * 同时它也让「本类到底读没读配置」在代码里一眼可见：字段是 {@code volatile} 的、只会被赋值一次。
     * <p>
     * 不可变：三个字段在构造时确定，此后只读。
     */
    private static final class Execution {

        /** 全局并发许可。 */
        private final Semaphore permits;

        /** 执行 run 的专用线程池：线程数按上界固定，积压交给有界队列。 */
        private final ThreadPoolExecutor executor;

        /** 等待区容量：提交被拒时要把这个值如实说出来，否则用户不知道该调哪个键。 */
        private final int queueCapacity;

        /**
         * 构造执行资源。
         *
         * @param permits       并发许可，不可为 {@code null}
         * @param executor      专用线程池，不可为 {@code null}
         * @param queueCapacity 等待区容量，保证为正
         */
        private Execution(Semaphore permits, ThreadPoolExecutor executor, int queueCapacity) {
            this.permits = permits;
            this.executor = executor;
            this.queueCapacity = queueCapacity;
        }
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
