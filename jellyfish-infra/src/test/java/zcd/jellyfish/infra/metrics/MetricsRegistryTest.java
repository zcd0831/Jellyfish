package zcd.jellyfish.infra.metrics;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsRegistry} 的单元测试：计数、仪表、快照与渲染。
 *
 * @author zcd
 */
class MetricsRegistryTest {

    /** 被测注册表。 */
    private final MetricsRegistry registry = new MetricsRegistry();

    @Test
    void add_should_accumulate_and_snapshot_should_expose_value() {
        // When
        registry.add("a", 2L);
        registry.increment("a");
        registry.increment("b");

        // Then
        MetricsSnapshot snapshot = registry.snapshot();
        assertEquals(3L, snapshot.getCounters().get("a"));
        assertEquals(1L, snapshot.getCounters().get("b"));
    }

    @Test
    void counter_should_return_the_same_adder_for_the_same_name() {
        // Then：同名必须落到同一个累加器，否则计数会被悄悄拆成两份
        assertEquals(registry.counter("x"), registry.counter("x"));
    }

    @Test
    void gauge_should_be_read_at_snapshot_time() {
        // Given
        AtomicInteger value = new AtomicInteger(1);
        registry.gauge("g", value::get);

        // When
        value.set(7);

        // Then：仪表是现读值，快照才求值
        assertEquals(7L, registry.snapshot().getGauges().get("g"));
    }

    @Test
    void snapshot_should_skip_only_the_broken_gauge() {
        // Given：一个坏仪表不该让整份诊断输出消失
        registry.gauge("bad", () -> {
            throw new IllegalStateException("炸了");
        });
        registry.gauge("good", () -> 5L);
        registry.increment("count");

        // When
        MetricsSnapshot snapshot = registry.snapshot();

        // Then
        assertFalse(snapshot.getGauges().containsKey("bad"));
        assertEquals(5L, snapshot.getGauges().get("good"));
        assertEquals(1L, snapshot.getCounters().get("count"));
    }

    @Test
    void snapshot_should_be_immutable_and_sorted() {
        // Given
        registry.increment("zeta");
        registry.increment("alpha");

        // When
        MetricsSnapshot snapshot = registry.snapshot();

        // Then
        assertEquals(Arrays.asList("alpha", "zeta"),
                new ArrayList<String>(snapshot.getCounters().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getCounters().put("x", 1L));
    }

    @Test
    void snapshot_should_be_empty_without_any_metric() {
        // When
        MetricsSnapshot snapshot = registry.snapshot();

        // Then
        assertTrue(snapshot.isEmpty());
        assertEquals("（暂无指标）", snapshot.render());
    }

    @Test
    void render_should_include_counters_and_gauges() {
        // Given
        registry.increment(MetricNames.COMMAND_EXECUTED);
        registry.gauge(MetricNames.EVENT_QUEUE_SIZE, () -> 2L);

        // When
        String text = registry.snapshot().render();

        // Then
        assertTrue(text.contains("[计数] command.executed=1"), text);
        assertTrue(text.contains("[仪表] eventChannel.queueSize=2"), text);
    }

    @Test
    void counter_should_reject_blank_name() {
        // When / Then：指标名是编程错误，立即抛
        assertThrows(JellyfishException.class, () -> registry.counter(" "));
        assertThrows(JellyfishException.class, () -> registry.counter(null));
    }

    @Test
    void gauge_should_reject_null_supplier() {
        // When / Then
        assertThrows(NullPointerException.class, () -> registry.gauge("x", null));
    }

    @Test
    void increment_should_be_thread_safe() throws InterruptedException {
        // Given
        int threads = 4;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        // When
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                for (int j = 0; j < perThread; j++) {
                    registry.increment("concurrent");
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // Then
        assertEquals((long) threads * perThread, registry.snapshot().getCounters().get("concurrent"));
    }
}
