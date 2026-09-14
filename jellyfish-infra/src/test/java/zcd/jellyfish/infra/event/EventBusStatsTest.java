package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventBusStats} 的单元测试：验证计数器累加、渲染与线程池指标绑定。
 *
 * @author zcd
 */
class EventBusStatsTest {

    /** 被测指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** 供线程池指标用例关闭的线程池。 */
    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void counters_should_start_at_zero() {
        // Then
        assertEquals(0L, stats.getPublishedEvents());
        assertEquals(0L, stats.getDroppedEvents());
        assertEquals(0L, stats.getDeadEventTypes());
        assertEquals(0L, stats.getUnmatchedNotifications());
        assertEquals(0L, stats.getSubscriberErrors());
        assertEquals(0L, stats.getDispatchedCallbacks());
        assertEquals(0L, stats.getFailedCallbacks());
        assertEquals(0L, stats.getNoHandlerCallbacks());
        assertEquals(0L, stats.getPendingReplayed());
        assertEquals(0L, stats.getPendingOverflow());
    }

    @Test
    void getters_should_reflect_incremented_counters() {
        // Given
        stats.publishedEvents.increment();
        stats.publishedEvents.increment();
        stats.droppedEvents.increment();
        stats.subscriberErrors.add(3L);

        // When / Then
        assertEquals(2L, stats.getPublishedEvents());
        assertEquals(1L, stats.getDroppedEvents());
        assertEquals(3L, stats.getSubscriberErrors());
    }

    @Test
    void render_should_include_counter_values() {
        // Given
        stats.publishedEvents.add(7L);
        stats.failedCallbacks.add(2L);

        // When
        String rendered = stats.render();

        // Then
        assertTrue(rendered.startsWith("eventBusStats{"));
        assertTrue(rendered.contains("publishedEvents=7"));
        assertTrue(rendered.contains("failedCallbacks=2"));
    }

    @Test
    void getActiveThreads_and_getQueueSize_should_return_zero_when_executor_not_bound() {
        // Then
        assertEquals(0, stats.getActiveThreads());
        assertEquals(0, stats.getQueueSize());
    }

    @Test
    void getActiveThreads_and_getQueueSize_should_reflect_bound_executor() throws Exception {
        // Given：单线程池 + 一个阻塞任务占住线程，再提交一个任务进入队列
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(4));
        stats.bindExecutor(executor);
        executor.execute(() -> {
            running.countDown();
            awaitUninterruptibly(release);
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        executor.execute(() -> {
            // 队列中的占位任务
        });

        // When / Then
        assertEquals(1, stats.getQueueSize());
        assertEquals(1, stats.getActiveThreads());

        release.countDown();
    }

    /**
     * 等待信号量，被中断时恢复中断标记，避免测试卡死。
     *
     * @param latch 待等待的信号量
     */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
