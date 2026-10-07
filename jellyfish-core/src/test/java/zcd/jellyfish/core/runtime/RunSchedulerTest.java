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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@link RunScheduler} 的单元测试：有界队列的吸收能力、满员时的拒绝、排队期被取消的处理，
 * 以及墙钟的起算点。
 * <p>
 * 这些行为一起回答的是同一个用户问题——「这一批派得比并发多会怎样」：多出来的应当排队等许可，
 * 而不是当场失败；排队等的时间不该算进它的墙钟；等它的那个回合要是已经结束了，也就不必再跑。
 * <p>
 * 直接调包私有的 {@link RunScheduler#submit} 而不是走 {@code AgentRuntime.spawn}：本类测的是调度，
 * 父子关系与登记由 {@code AgentRuntimeTest} 覆盖。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
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

    /**
     * 构造一套调度装配。
     *
     * @param maxConcurrentRuns 全局并发许可数
     * @param maxQueuedRuns     排队上限
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒），{@code <= 0} 表示不看门狗
     * @return 调度器
     */
    private RunScheduler scheduler(int maxConcurrentRuns, int maxQueuedRuns, long runTimeoutMillis) {
        SubAgentSettings settings = new SubAgentSettings(true, 0, 8, 8, maxConcurrentRuns, maxQueuedRuns,
                runTimeoutMillis, 0L, 0L, null, null);
        lenient().when(runtimeConfig.getSubAgentSettings()).thenReturn(settings);
        return new RunScheduler(contexts, registry, new RunEventBus(), runtimeConfig);
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
        registry.bindCanceller(runId, handle::cancel);
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
