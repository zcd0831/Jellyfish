package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventRejectedExecutionHandler} 的单元测试：验证「丢弃 + 计数」策略。
 * <p>
 * 刻意断言「任务没有在调用线程执行」：异步通道的契约是允许丢弃，不允许把订阅者拖回调用线程。
 *
 * @author zcd
 */
class EventRejectedExecutionHandlerTest {

    @Test
    void rejectedExecution_should_drop_and_count_without_running_inline() {
        // Given
        EventChannelStats stats = new EventChannelStats();
        EventRejectedExecutionHandler handler = new EventRejectedExecutionHandler(stats);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1), new EventThreadFactory(), handler);
        boolean[] ran = {false};

        // When
        handler.rejectedExecution(() -> ran[0] = true, executor);

        // Then
        assertFalse(ran[0]);
        assertEquals(1L, stats.getDroppedEvents());
        executor.shutdownNow();
    }

    @Test
    void rejectedExecution_should_count_every_drop() {
        // Given
        EventChannelStats stats = new EventChannelStats();
        EventRejectedExecutionHandler handler = new EventRejectedExecutionHandler(stats);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(1), new EventThreadFactory());

        // When
        handler.rejectedExecution(() -> {
        }, executor);
        handler.rejectedExecution(() -> {
        }, executor);

        // Then
        assertTrue(stats.getDroppedEvents() == 2L);
        executor.shutdownNow();
    }
}
