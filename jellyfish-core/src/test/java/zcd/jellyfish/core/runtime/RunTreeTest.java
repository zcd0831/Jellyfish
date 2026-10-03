package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunTree} 的单元测试：派生名额的原子占用与 token 账。
 * <p>
 * 最要紧的一条是并发下的上限：多个父 run 同时派生时不能超发，否则「配额」形同虚设。
 *
 * @author zcd
 */
class RunTreeTest {

    @Test
    void tryAcquireSpawn_should_stop_at_budget() {
        // Given
        RunTree tree = new RunTree(8, 2, 0L, 0L);

        // When / Then
        assertTrue(tree.tryAcquireSpawn(0));
        assertTrue(tree.tryAcquireSpawn(0));
        assertFalse(tree.tryAcquireSpawn(0));
        assertEquals(2, tree.getSpawnCount());
    }

    @Test
    void tryAcquireSpawn_should_reject_at_depth_limit() {
        // Given：深度上限 1
        RunTree tree = new RunTree(1, 8, 0L, 0L);

        // When / Then：已经在第 1 层，不能再往下派
        assertFalse(tree.tryAcquireSpawn(1));
        assertTrue(tree.tryAcquireSpawn(0));
    }

    @Test
    void addTokens_should_ignore_non_positive() {
        // Given
        RunTree tree = new RunTree(2, 8, 0L, 0L);

        // When / Then
        assertEquals(100L, tree.addTokens(100L));
        assertEquals(100L, tree.addTokens(0L));
        assertEquals(100L, tree.addTokens(-5L));
        assertEquals(100L, tree.getTreeTokens());
    }

    @Test
    @Timeout(20)
    void tryAcquireSpawn_should_not_exceed_budget_under_concurrency() throws Exception {
        // Given：32 个线程抢 10 个名额
        RunTree tree = new RunTree(8, 10, 0L, 0L);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (tree.tryAcquireSpawn(0)) {
                        granted.incrementAndGet();
                    }
                    return null;
                }));
            }

            // When
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Then：恰好 10 个，不多不少
        assertEquals(10, granted.get());
        assertEquals(10, tree.getSpawnCount());
    }
}
