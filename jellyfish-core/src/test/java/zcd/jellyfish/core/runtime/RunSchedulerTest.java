package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RunScheduler} 的单元测试：有界队列的吸收能力、满员时的拒绝、排队期被取消的处理，
 * 墙钟的起算点，以及「配置在什么时候读」。
 * <p>
 * 这些行为一起回答的是同一个用户问题——「这一批派得比并发多会怎样」：多出来的应当排队等许可，
 * 而不是当场失败；排队等的时间不该算进它的墙钟；等它的那个回合要是已经结束了，也就不必再跑。
 * 最后一组用例守的是另一件更隐蔽的事：<b>装配发生在配置装载之前</b>，因此构造期读到的参数
 * 永远只是缺省值——「配了却完全没生效」不会报错、不会有日志，只会让人一直怀疑自己写错了键。
 * <p>
 * 直接调包私有的 {@link RunScheduler#submit} 而不是走 {@code AgentRuntime.spawn}：本类测的是调度，
 * 父子关系与登记由 {@code AgentRuntimeTest} 覆盖。
 * <p>
 * <b>类级 {@code @Timeout}</b>：本类多条用例走 {@code AgentRunHandle.await()}（{@code CountDownLatch.await()}
 * 无时限），而它要防的正是「run 被丢掉、句柄永不落终态」——那种回归表现为<b>挂死</b>。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RunSchedulerTest {

    /** 运行时配置门面：只用来给调度器喂 {@code subAgent} 段。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 真实登记表。 */
    private RunRegistry registry;

    /** 真实上下文持有者。 */
    private RunContextHolder contexts;

    /** 被测对象。 */
    private RunScheduler scheduler;

    /** 本次 run 所属的树账本。 */
    private RunTree tree;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        contexts = new RunContextHolder();
        // 委派深度不给（0）：调度本身不判定深度，用 0 让线程数上界恰好等于并发数，时序才好推
        tree = new RunTree(0, 8, 0L, 0L);
    }

    @Test
    @DisplayName("并发满员时多出来的 run 排队执行，而不是当场失败")
    void submit_should_queue_extra_runs_instead_of_failing_when_concurrency_is_full() {
        // Given：并发 1、队列 8、不看门狗（本用例只关心进不进得了队列）
        scheduler = scheduler(1, 8, 0L);

        // When：一口气提交三个
        AgentRunHandle first = submitRun(done());
        AgentRunHandle second = submitRun(done());
        AgentRunHandle third = submitRun(done());

        // Then：三个都跑完——「这一批派得比并发多」不该等于「多出来的当场失败」
        assertEquals(AgentRunStatus.DONE, await(first).getStatus());
        assertEquals(AgentRunStatus.DONE, await(second).getStatus());
        assertEquals(AgentRunStatus.DONE, await(third).getStatus());
    }

    @Test
    @DisplayName("线程与队列都满时才拒绝，理由里带上该调哪个键")
    void submit_should_fail_only_when_queue_is_full() {
        // Given：并发 1、队列只能装 1 个；第一个 run 占住线程，第二个占住队列
        scheduler = scheduler(1, 1, 0L);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunHandle running = submitRun(blockingUntil(release));
        AgentRunHandle queued = submitRun(done());

        // When：第三个来了
        AgentRunHandle rejected = submitRun(done());

        // Then：当场失败，但理由是可修的（说明是排队满了、该调哪个键），而不是静默丢弃
        AgentRunResult result = await(rejected);
        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("排队已满"), String.valueOf(result.getError()));
        assertTrue(result.getError().contains("maxQueuedRuns=1"), String.valueOf(result.getError()));

        // 排队的那一个不受影响，照跑
        release.countDown();
        assertEquals(AgentRunStatus.DONE, await(running).getStatus());
        assertEquals(AgentRunStatus.DONE, await(queued).getStatus());
    }

    @Test
    @DisplayName("排队期间被取消的 run 直接落终态，不再执行执行体")
    void run_should_skip_body_when_cancelled_while_queued() {
        // Given：并发 1，第一个 run 占住线程，第二个在队列里等
        scheduler = scheduler(1, 8, 0L);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunHandle running = submitRun(blockingUntil(release));
        AtomicBoolean bodyRan = new AtomicBoolean(false);
        AgentRunHandle queued = submitRun(handle -> {
            bodyRan.set(true);
            return AgentRunResult.of(AgentRunStatus.DONE, "不该跑到这里", 1, null, null);
        });

        // When：回合结束了（用户中止 / 父 run 失败），排队中的那个被级联取消
        queued.cancel();
        release.countDown();

        // Then：它如实落「已取消」，而且一轮都没跑——结果没人读，跑了只是白占许可与 token
        assertEquals(AgentRunStatus.CANCELLED, await(queued).getStatus());
        assertFalse(bodyRan.get());
        assertEquals(AgentRunStatus.DONE, await(running).getStatus());
    }

    @Test
    @Timeout(30)
    @DisplayName("墙钟从开始执行起算：排队等许可的时间不算它跑的时间")
    void runTimeout_should_start_when_run_begins_executing() {
        // Given：并发 1、墙钟 200ms。第一个 run 一直不收敛（会被自己的墙钟掐断），第二个只能排队
        scheduler = scheduler(1, 8, 200L);
        AgentRunHandle running = submitRun(handle -> {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (!handle.isCancelled() && System.currentTimeMillis() < deadline) {
                sleepQuietly();
            }
            return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
        });
        AgentRunHandle queued = submitRun(done());

        // Then（一）：排队中的 run 处在「已登记、尚未执行」，而不是被算成在跑
        assertEquals(AgentRunStatus.PENDING, registry.snapshot(queued.getRunId())
                .orElseThrow(AssertionError::new).getStatus());

        // Then（二）：第一个被自己的墙钟掐断，原因落在 error 上（回灌时要如实说清为什么停的）
        AgentRunResult timedOut = await(running);
        assertEquals(AgentRunStatus.TRUNCATED, timedOut.getStatus());
        assertTrue(timedOut.getError().contains("墙钟"), String.valueOf(timedOut.getError()));

        // Then（三）：第二个排了 200ms 以上才轮到，仍然正常跑完——
        // 墙钟若从提交时刻起算，它一开头就已经超时了
        assertEquals(AgentRunStatus.DONE, await(queued).getStatus());
    }

    @Test
    @Timeout(30)
    @DisplayName("关闭时取消在途 run，并等它走到终态才返回")
    void close_should_cancel_in_flight_run_and_wait_for_it() {
        // Given：一个不自己结束、只有被取消才收敛的 run
        scheduler = scheduler(1, 8, 0L);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean bodyExited = new AtomicBoolean(false);
        AgentRunHandle running = submitRun(handle -> {
            started.countDown();
            long deadline = System.currentTimeMillis() + 10_000L;
            while (!handle.isCancelled() && System.currentTimeMillis() < deadline) {
                sleepQuietly();
            }
            bodyExited.set(true);
            return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
        });
        awaitLatch(started);

        // When
        scheduler.close();

        // Then：run 落了终态，且执行体真的已经退出——「关闭返回」意味着「不再有 run 在写子会话、调插件」
        assertEquals(AgentRunStatus.CANCELLED, await(running).getStatus());
        assertTrue(bodyExited.get(), "关闭返回时执行体必须已经退出");
    }

    @Test
    @Timeout(30)
    @DisplayName("关闭时把排队中、从未开跑的 run 也收尾，等它的人不会永远等下去")
    void close_should_finish_queued_run_that_never_started() {
        // Given：并发 1、不看门狗。第一个 run 占住唯一的线程且（此处故意）不理会取消，第二个只能排队
        scheduler = scheduler(1, 8, 0L);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunHandle running = submitRun(blockingUntil(release));
        AtomicBoolean queuedBodyRan = new AtomicBoolean(false);
        AgentRunHandle queued = submitRun(handle -> {
            queuedBodyRan.set(true);
            return AgentRunResult.of(AgentRunStatus.DONE, "ok", 1, null, null);
        });
        assertEquals(AgentRunStatus.PENDING, registry.snapshot(queued.getRunId())
                .orElseThrow(AssertionError::new).getStatus());

        // When
        scheduler.close();

        // Then：排队那一个的执行体从未跑过，但它必须有终态——它在 shutdownNow 之后不会有人来收尾，
        // 而拿不到结果的表现是父回合永久挂住，不是一条错误
        assertEquals(AgentRunStatus.CANCELLED, await(queued).getStatus(),
                "排队中被丢弃的 run 由兜底收尾，如实按「已取消」记");
        assertFalse(queuedBodyRan.get(), "排队中被取消的 run 不该再被执行");

        // 收尾：放掉第一个，免得后台线程继续挂着。它此刻落 DONE 还是 CANCELLED 取决于
        // 「中断先到」还是「取消先到」——两种都如实（它确实被取消过，也确实是执行体自己返回的），
        // 因此这里只断言它一定已经落终态，不断言是哪一种
        release.countDown();
        assertTrue(await(running).getStatus().isTerminal());
    }

    @Test
    void close_should_be_idempotent() {
        scheduler = scheduler(1, 8, 0L);
        AgentRunHandle done = submitRun(done());
        assertEquals(AgentRunStatus.DONE, await(done).getStatus());

        scheduler.close();
        scheduler.close();
    }

    @Test
    void submit_after_close_should_fail_with_shutdown_reason() {
        // Given：内核已经在关闭
        scheduler = scheduler(1, 8, 0L);
        scheduler.close();

        // When：还有人想派一个 run
        AgentRunResult result = await(submitRun(done()));

        // Then：如实说「内核正在关闭」，而不是编一句「排队已满」让人去调配置键
        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("关闭"), String.valueOf(result.getError()));
        assertFalse(result.getError().contains("排队已满"), String.valueOf(result.getError()));
    }

    @Test
    @DisplayName("构造期不读 subAgent 配置：此刻快照还没装载，读了只会拿到缺省值")
    void constructor_should_not_read_subagent_settings() {
        // When：只构造，不提交任何 run
        scheduler = new RunScheduler(contexts, registry, new RunEventBus(), runtimeConfig);

        // Then：一次都没问过配置。装配发生在 runtimeConfig.refresh() 之前，构造期读到的永远是缺省值——
        // 那正是「runTimeoutMillis 写了 20 分钟却仍按 300 秒掐」的成因，而这个失效没有任何症状
        verify(runtimeConfig, never()).getSubAgentSettings();
    }

    @Test
    @Timeout(30)
    @DisplayName("执行资源在第一次提交时按当时的配置建：构造之后才配上的并发同样生效")
    void execution_should_be_created_with_config_at_first_submit() {
        // Given：构造时配置还没装载（真实启动就是这样：快照由 refresh() 稍后装上）
        scheduler = new RunScheduler(contexts, registry, new RunEventBus(), runtimeConfig);

        // When：配置到位之后才提交，并发只给 1
        when(runtimeConfig.getSubAgentSettings()).thenReturn(settings(1, 0, 8, 0L));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunHandle running = submitRun(handle -> {
            started.countDown();
            return blockingUntil(release).run(handle);
        });
        awaitLatch(started);
        AgentRunHandle queued = submitRun(done());

        // Then：第二个进了等待区——并发 1 是「提交那一刻」的配置说了算，放掉第一个之后它照跑
        assertEquals(AgentRunStatus.PENDING, registry.snapshot(queued.getRunId())
                .orElseThrow(AssertionError::new).getStatus());
        release.countDown();
        assertEquals(AgentRunStatus.DONE, await(running).getStatus());
        assertEquals(AgentRunStatus.DONE, await(queued).getStatus());
    }

    @Test
    @Timeout(30)
    @DisplayName("墙钟按 run 开始时的配置取：构造之后改配置，下一个 run 就按新值计时")
    void runTimeout_should_follow_config_change_after_construction() {
        // Given：构造时写的是「不看门狗」
        scheduler = scheduler(1, 8, 0L);
        assertEquals(AgentRunStatus.DONE, await(submitRun(done())).getStatus());

        // When：配置改成 200ms 之后，再来一个自己不会收敛的 run
        when(runtimeConfig.getSubAgentSettings()).thenReturn(settings(1, 0, 8, 200L));
        AgentRunHandle slow = submitRun(handle -> {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (!handle.isCancelled() && System.currentTimeMillis() < deadline) {
                sleepQuietly();
            }
            return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
        });

        // Then：它被 200ms 的墙钟掐断，且原因里写的也是 200 ms——上限读的是「开始跑的这一刻」，
        // 不是构造期那份（那份永远是缺省值）
        AgentRunResult timedOut = await(slow);
        assertEquals(AgentRunStatus.TRUNCATED, timedOut.getStatus());
        assertTrue(String.valueOf(timedOut.getError()).contains("200 ms"),
                String.valueOf(timedOut.getError()));
    }

    @Test
    @Timeout(30)
    @DisplayName("等并发许可期间被取消：立刻落终态，父回合不会一直等下去")
    void run_should_settle_immediately_when_cancelled_while_waiting_for_permit() {
        // Given：并发 1、池上界 2（深度 1）——第一个 run 拿住许可不放，第二个拿到线程却只能等许可
        scheduler = scheduler(1, 1, 8, 0L);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunHandle running = submitRun(blockingUntil(release));
        AtomicBoolean waitingBodyRan = new AtomicBoolean(false);
        AgentRunHandle waiting = submitRun(handle -> {
            waitingBodyRan.set(true);
            return AgentRunResult.of(AgentRunStatus.DONE, "ok", 1, null, null);
        });
        // 让第二个真正卡到「取许可」那一步：抢在它拿到线程之前取消，走的是另一条（同样该收敛的）路径
        sleepQuietly();

        // When：回合结束了（用户按了中断 / 父 run 失败），等许可的那个也被取消
        waiting.cancel();

        // Then：它必须<b>当场</b>收敛。旧实现卡在不可中断的取许可上，要等前一个 run 跑完才认账——
        // 于是它的句柄长时间不落终态，等它的父回合与驱动它的插件就迟迟拿不到结果，
        // 界面上看起来正是「按了中断没反应」。这里用 3 秒的硬上限把这个回归钉住。
        AgentRunResult result = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> await(waiting),
                "等许可期间被取消的 run 必须立刻收敛");
        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
        assertFalse(waitingBodyRan.get(), "等许可期间被取消的 run 不该再执行执行体");

        // 收尾：放掉第一个，免得后台线程继续挂着
        release.countDown();
        assertEquals(AgentRunStatus.DONE, await(running).getStatus());
    }

    /**
     * 等待一个闩锁开闸。
     *
     * @param latch 闩锁
     */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "等待超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }

    /**
     * 构造一套调度装配（委派深度 0：线程数上界恰好等于并发数）。
     *
     * @param maxConcurrentRuns 全局并发许可数
     * @param maxQueuedRuns     排队上限
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），{@code <= 0} 表示不看门狗
     * @return 调度器
     */
    private RunScheduler scheduler(int maxConcurrentRuns, int maxQueuedRuns, long runTimeoutMillis) {
        return scheduler(maxConcurrentRuns, 0, maxQueuedRuns, runTimeoutMillis);
    }

    /**
     * 构造一套调度装配。
     * <p>
     * <b>深度不是随便给的</b>：池上界 = {@code 并发 × (深度 + 1)}。深度 0 时池只有并发那么多条线程，
     * 「等许可」的 run 其实还留在执行器的队列里（走的是另一条收敛路径）；要真正压到
     * {@code acquirePermit} 上，就得让池比许可多（深度 ≥ 1）。
     *
     * @param maxConcurrentRuns 全局并发许可数
     * @param maxDepth          委派深度（决定池上界）
     * @param maxQueuedRuns     排队上限
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），{@code <= 0} 表示不看门狗
     * @return 调度器
     */
    private RunScheduler scheduler(int maxConcurrentRuns, int maxDepth, int maxQueuedRuns,
                                   long runTimeoutMillis) {
        lenient().when(runtimeConfig.getSubAgentSettings())
                .thenReturn(settings(maxConcurrentRuns, maxDepth, maxQueuedRuns, runTimeoutMillis));
        return new RunScheduler(contexts, registry, new RunEventBus(), runtimeConfig);
    }

    /**
     * 造一份 {@code subAgent} 段。
     *
     * @param maxConcurrentRuns 全局并发许可数
     * @param maxDepth          委派深度
     * @param maxQueuedRuns     排队上限
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），{@code <= 0} 表示不看门狗
     * @return 子代理设置
     */
    private static SubAgentSettings settings(int maxConcurrentRuns, int maxDepth, int maxQueuedRuns,
                                             long runTimeoutMillis) {
        return new SubAgentSettings(true, maxDepth, 8, 8, maxConcurrentRuns, maxQueuedRuns,
                runTimeoutMillis, 0L, 0L, null, null);
    }
    /**
     * 登记并提交一个 run。
     *
     * @param body 执行体
     * @return run 句柄
     */
    private AgentRunHandle submitRun(AgentRunBody body) {
        String runId = registry.register(new AgentRunRequest("s-parent", "coder", "s-child", "call-1"), null, null);
        AgentRunHandle handle = new AgentRunHandle(runId);
        registry.bindHandle(runId, handle);
        scheduler.submit(runId, body, tree, 0, runId, handle);
        return handle;
    }

    /**
     * 立即返回「完成」的执行体。
     *
     * @return 执行体
     */
    private static AgentRunBody done() {
        return handle -> AgentRunResult.of(AgentRunStatus.DONE, "ok", 1, null, null);
    }

    /**
     * 阻塞到闩锁开闸才返回「完成」的执行体：用来占住线程与并发许可。
     *
     * @param release 释放闩锁
     * @return 执行体
     */
    private static AgentRunBody blockingUntil(CountDownLatch release) {
        return handle -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentRunResult.of(AgentRunStatus.DONE, "ok", 1, null, null);
        };
    }

    /**
     * 等一个 run 终结。
     *
     * @param handle run 句柄
     * @return 终态结果
     */
    private static AgentRunResult await(AgentRunHandle handle) {
        return handle.await();
    }

    /**
     * 短暂让出 CPU：等看门狗到点时用，避免忙等把测试机器跑满。
     */
    private static void sleepQuietly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
