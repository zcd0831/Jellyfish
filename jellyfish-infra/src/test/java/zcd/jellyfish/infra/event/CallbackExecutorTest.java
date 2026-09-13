package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.PluginRequest;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackExecutor} 的单元测试：验证正常执行、异常回传、逐处理器超时与队列拒绝。
 *
 * @author zcd
 */
class CallbackExecutorTest {

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** 待关闭的执行器。 */
    private CallbackExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void execute_should_return_result_when_handler_succeeds() throws Exception {
        // Given
        executor = new CallbackExecutor(EventBusOptions.defaults(), stats);

        // When
        Object result = executor.execute(callback(), cb -> "ok");

        // Then
        assertEquals("ok", result);
    }

    @Test
    void execute_should_rethrow_runtime_exception_when_handler_fails() {
        // Given
        executor = new CallbackExecutor(EventBusOptions.defaults(), stats);

        // When / Then
        CallbackHandler<Callback<Object>, Object> handler = cb -> {
            throw new IllegalStateException("boom");
        };
        assertThrows(IllegalStateException.class, () -> executor.execute(callback(), handler));
    }

    @Test
    void execute_should_rethrow_checked_exception_when_handler_fails() {
        // Given
        executor = new CallbackExecutor(EventBusOptions.defaults(), stats);

        // When / Then
        CallbackHandler<Callback<Object>, Object> handler = cb -> {
            throw new IOException("io");
        };
        IOException exception = assertThrows(IOException.class, () -> executor.execute(callback(), handler));
        assertEquals("io", exception.getMessage());
    }

    @Test
    void execute_should_timeout_and_count_when_handler_exceeds_per_handler_timeout() {
        // Given：超时 50ms，处理器阻塞 5s
        EventBusOptions options = EventBusOptions.builder().callbackPerHandlerTimeoutMillis(50L).build();
        executor = new CallbackExecutor(options, stats);
        CallbackHandler<Callback<Object>, Object> handler = cb -> {
            Thread.sleep(5000L);
            return "late";
        };

        // When / Then
        JellyfishException exception = assertThrows(JellyfishException.class,
                () -> executor.execute(callback(), handler));
        assertTrue(exception.getMessage().contains("timed out"));
        assertEquals(1L, stats.getTimedOutCallbacks());
    }

    @Test
    void execute_should_reject_and_count_when_queue_is_full() throws Exception {
        // Given：单线程 + 队列容量 1；一个任务占住线程，一个任务在队列中，第三个必然被拒
        EventBusOptions options = EventBusOptions.builder()
                .callbackCorePoolSize(1).callbackMaxPoolSize(1).callbackQueueCapacity(1)
                .callbackPerHandlerTimeoutMillis(10000L).build();
        executor = new CallbackExecutor(options, stats);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CallbackHandler<Callback<Object>, Object> blocking = cb -> {
            holding.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "held";
        };
        Thread first = new Thread(() -> runQuietly(blocking));
        Thread second = new Thread(() -> {
            queued.countDown();
            runQuietly(blocking);
        });
        first.start();
        second.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        assertTrue(queued.await(5, TimeUnit.SECONDS));
        waitUntilQueued();

        // When / Then
        JellyfishException exception = assertThrows(JellyfishException.class,
                () -> executor.execute(callback(), cb -> "quick"));
        assertTrue(exception.getMessage().contains("rejected"));
        assertEquals(1L, stats.getRejectedCallbacks());

        release.countDown();
        first.join(5000L);
        second.join(5000L);
    }

    /**
     * 等待第二个任务真正进入队列，避免依赖固定睡眠时间。
     */
    private void waitUntilQueued() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.queuedTaskCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(executor.queuedTaskCount() > 0, "第二个任务应已进入队列");
    }

    /**
     * 在独立线程中执行一次阻塞任务，忽略异常以避免测试线程受影响。
     *
     * @param handler 阻塞处理器
     */
    private void runQuietly(CallbackHandler<Callback<Object>, Object> handler) {
        try {
            executor.execute(callback(), handler);
        } catch (Exception e) {
            // 测试只关心中止与拒绝，忽略线程内异常
        }
    }

    /**
     * 构造测试回调。
     *
     * @return 测试回调
     */
    private static PluginRequest callback() {
        return new PluginRequest("test", Object.class, null);
    }
}
