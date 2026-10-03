package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunEventBus} 的单元测试：扇出、隔离与退订。
 * <p>
 * 最要紧的一条是隔离：一个坏订阅者不该让别的订阅者收不到，更不该让 run 本身失败。
 *
 * @author zcd
 */
class RunEventBusTest {

    @Test
    void publish_should_deliver_to_every_subscriber() {
        // Given
        RunEventBus bus = new RunEventBus();
        List<String> kinds = new ArrayList<String>();
        List<String> runIds = new ArrayList<String>();
        bus.subscribe(event -> kinds.add(event.getKind().name()));
        bus.subscribe(event -> runIds.add(event.getRunId()));

        // When
        bus.publish(AgentRunEvent.started(snapshot("r1")));

        // Then
        assertEquals(Collections.singletonList("STARTED"), kinds);
        assertEquals(Collections.singletonList("r1"), runIds);
    }

    @Test
    void subscriber_exception_should_be_isolated() {
        // Given：第一个订阅者抛错
        RunEventBus bus = new RunEventBus();
        List<String> delivered = new ArrayList<String>();
        bus.subscribe(event -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(event -> delivered.add(event.getRunId()));

        // When
        bus.publish(AgentRunEvent.started(snapshot("r1")));

        // Then：后面的订阅者照常收到
        assertEquals(Collections.singletonList("r1"), delivered);
    }

    @Test
    void close_should_stop_delivery() {
        // Given
        RunEventBus bus = new RunEventBus();
        List<String> delivered = new ArrayList<String>();
        Subscription subscription = bus.subscribe(event -> delivered.add(event.getRunId()));

        // When
        subscription.close();
        bus.publish(AgentRunEvent.started(snapshot("r1")));

        // Then
        assertTrue(delivered.isEmpty());
    }

    @Test
    void publish_without_subscribers_should_be_noop() {
        // When / Then：无人订阅时发布不应抛错
        new RunEventBus().publish(AgentRunEvent.started(snapshot("r1")));
    }

    /**
     * 构造一个 run 快照。
     *
     * @param runId run 标识
     * @return 快照
     */
    private static AgentRunSnapshot snapshot(String runId) {
        return new AgentRunSnapshot(runId, null, runId, "s-parent", "coder", "s-child", null,
                AgentRunStatus.RUNNING, System.currentTimeMillis(), 0L, 0, 0L);
    }
}
