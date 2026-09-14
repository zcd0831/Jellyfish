package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventChannelStats} 的单元测试：验证计数读取、线程池指标与渲染文本。
 *
 * @author zcd
 */
class EventChannelStatsTest {

    /** 被测指标。 */
    private final EventChannelStats stats = new EventChannelStats();

    @Test
    void counters_should_start_at_zero() {
        // Then
        assertEquals(0L, stats.getPublishedEvents());
        assertEquals(0L, stats.getDroppedEvents());
        assertEquals(0L, stats.getUnmatchedNotifications());
        assertEquals(0L, stats.getSubscriberErrors());
        assertEquals(0L, stats.getPendingReplayed());
        assertEquals(0L, stats.getPendingOverflow());
        assertEquals(0, stats.getActiveThreads());
        assertEquals(0, stats.getQueueSize());
    }

    @Test
    void getters_should_reflect_incremented_counters() {
        // When
        stats.publishedEvents.increment();
        stats.droppedEvents.increment();
        stats.unmatchedNotifications.increment();
        stats.subscriberErrors.add(2);
        stats.pendingReplayed.increment();
        stats.pendingOverflow.increment();

        // Then
        assertEquals(1L, stats.getPublishedEvents());
        assertEquals(1L, stats.getDroppedEvents());
        assertEquals(1L, stats.getUnmatchedNotifications());
        assertEquals(2L, stats.getSubscriberErrors());
        assertEquals(1L, stats.getPendingReplayed());
        assertEquals(1L, stats.getPendingOverflow());
    }

    @Test
    void bound_executor_should_expose_thread_pool_metrics() {
        // Given
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(4), new EventThreadFactory());

        // When
        stats.bindExecutor(executor);

        // Then
        assertEquals(0, stats.getActiveThreads());
        assertEquals(0, stats.getQueueSize());
        executor.shutdownNow();
    }

    @Test
    void render_should_include_every_counter() {
        // Given
        stats.publishedEvents.increment();

        // When
        String rendered = stats.render();

        // Then
        assertTrue(rendered.startsWith("eventChannelStats{"));
        assertTrue(rendered.contains("publishedEvents=1"));
        assertTrue(rendered.contains("droppedEvents=0"));
        assertTrue(rendered.contains("pendingOverflow=0"));
    }
}
