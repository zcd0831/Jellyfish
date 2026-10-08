package zcd.jellyfish.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseThreadFactory} 的单元测试。
 * <p>
 * 两条都要钉住：名字让人在 jstack 里一眼认出这些线程是谁（否则排查「谁把线程占满了」只能靠猜），
 * 守护属性保证它们阻塞在写操作上时不会拖住 JVM 退出。
 *
 * @author zcd
 */
@DisplayName("SSE 线程工厂")
class SseThreadFactoryTest {

    @Test
    @DisplayName("线程应带统一前缀与递增序号")
    void newThread_should_nameThreadsWithPrefixAndSequence() {
        SseThreadFactory factory = new SseThreadFactory();

        Thread first = factory.newThread(() -> { });
        Thread second = factory.newThread(() -> { });

        assertTrue(first.getName().startsWith("jellyfish-sse-"), first.getName());
        assertTrue(second.getName().startsWith("jellyfish-sse-"), second.getName());
        assertNotEquals(first.getName(), second.getName(), "序号必须递增，否则看不出是几条线程");
    }

    @Test
    @DisplayName("线程必须是守护线程：它们可能长时间阻塞在写操作上")
    void newThread_should_beDaemon() {
        assertTrue(new SseThreadFactory().newThread(() -> { }).isDaemon());
    }

    @Test
    @DisplayName("池里的线程应真的按这个工厂命名")
    void pool_should_useFactoryNames() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2, new SseThreadFactory());
        try {
            AtomicReference<String> name = new AtomicReference<String>();
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(() -> {
                name.set(Thread.currentThread().getName());
                done.countDown();
            });

            assertTrue(done.await(5, TimeUnit.SECONDS), "任务没有跑起来");
            assertTrue(name.get().startsWith("jellyfish-sse-"), name.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
