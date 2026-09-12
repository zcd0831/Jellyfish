package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventRejectedExecutionHandler} 的单元测试：验证非事件线程内联兜底、事件线程丢弃记账。
 *
 * @author zcd
 */
class EventRejectedExecutionHandlerTest {

    @Test
    void rejectedExecution_should_run_inline_when_not_event_thread() throws Exception {
        // Given
        EventBusStats stats = new EventBusStats();
        EventRejectedExecutionHandler handler = new EventRejectedExecutionHandler(stats);
        AtomicBoolean executed = new AtomicBoolean();
        ThreadPoolExecutor executor = newExecutor();
        try {
            // When
            runInThread("junit-worker", () -> handler.rejectedExecution(() -> executed.set(true), executor));
        } finally {
            executor.shutdownNow();
        }

        // Then
        assertTrue(executed.get());
        assertEquals(0L, stats.getDroppedEvents());
    }

    @Test
    void rejectedExecution_should_drop_and_count_when_event_thread() throws Exception {
        // Given
        EventBusStats stats = new EventBusStats();
        EventRejectedExecutionHandler handler = new EventRejectedExecutionHandler(stats);
        AtomicBoolean executed = new AtomicBoolean();
        ThreadPoolExecutor executor = newExecutor();
        try {
            // When：线程名带事件前缀，模拟事件线程内再次提交被拒
            runInThread(EventThreadFactory.NAME_PREFIX + "test",
                    () -> handler.rejectedExecution(() -> executed.set(true), executor));
        } finally {
            executor.shutdownNow();
        }

        // Then
        assertFalse(executed.get());
        assertEquals(1L, stats.getDroppedEvents());
    }

    /**
     * 在指定线程名中执行动作并等待结束。
     *
     * @param name   线程名
     * @param action 待执行动作
     * @throws InterruptedException 等待线程结束时被中断
     */
    private static void runInThread(String name, Runnable action) throws InterruptedException {
        Thread thread = new Thread(action, name);
        thread.start();
        thread.join();
    }

    /**
     * 创建单线程有界队列线程池，仅作为拒绝策略的入参。
     *
     * @return 线程池
     */
    private static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));
    }
}
