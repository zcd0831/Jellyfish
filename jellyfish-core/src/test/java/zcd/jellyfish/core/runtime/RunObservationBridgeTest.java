package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.AgentRunProgressEvent;
import zcd.jellyfish.api.subagent.DelegationStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunObservationBridge} 的单元测试：两端都桥、每一步都不桥、终态分类归一、订阅幂等与可退订。
 * <p>
 * 用真实的 {@link RunEventBus}（证明订阅确实挂在总线上）与一个记录用的假
 * {@link EventPublisher}（同步、无异步等待，因此断言是确定性的）。
 *
 * @author zcd
 */
@DisplayName("run 观测桥")
class RunObservationBridgeTest {

    /** 发布到通知通道的事件。 */
    private final List<JellyfishEvent> published = new ArrayList<JellyfishEvent>();

    /** 真实内核总线。 */
    private RunEventBus bus;

    /** 被测桥。 */
    private RunObservationBridge bridge;

    @BeforeEach
    void setUp() {
        bus = new RunEventBus();
        bridge = new RunObservationBridge(bus, new EventPublisher() {

            @Override
            public void publish(JellyfishEvent event) {
                published.add(event);
            }
        });
    }

    @Test
    @DisplayName("开始事件：逐字段搬运，且没有结局分类")
    void started_shouldCarryIdentityAndNoOutcome() {
        // Given
        bridge.start();

        // When
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));

        // Then
        assertEquals(1, published.size());
        AgentRunProgressEvent event = (AgentRunProgressEvent) published.get(0);
        assertEquals(AgentRunProgressEvent.Kind.STARTED, event.getKind());
        assertTrue(event.isFinished() == false);
        assertNull(event.getOutcome(), "还没结束就不该有结局");
        assertEquals("run-1", event.getRunId());
        assertEquals("run-root", event.getRootRunId());
        assertEquals("s-parent", event.getParentSessionId());
        // 会话标识就是父会话：订阅方的过滤条件因此是一行
        assertEquals("s-parent", event.getSessionId());
        assertEquals("scout", event.getAgentId());
        assertEquals(0, event.getRounds());
        assertEquals(0L, event.getTotalTokens());
    }

    @Test
    @DisplayName("结束事件：带上轮数、用量与归一后的结局")
    void finished_shouldCarryOutcomeAndUsage() {
        // Given
        bridge.start();

        // When
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.DONE, 4, 1200L)));

        // Then
        AgentRunProgressEvent event = (AgentRunProgressEvent) published.get(0);
        assertTrue(event.isFinished());
        assertEquals(DelegationStatus.COMPLETED, event.getOutcome());
        assertEquals(4, event.getRounds());
        assertEquals(1200L, event.getTotalTokens());
    }

    @Test
    @DisplayName("终态分类按词表归一：截断 / 取消 / 被拦 / 失败各归各的")
    void finished_shouldNormaliseTerminalStatus() {
        // Given
        bridge.start();

        // When
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.TRUNCATED, 1, 1L)));
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.CANCELLED, 1, 1L)));
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.BLOCKED, 0, 0L)));
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.FAILED, 1, 1L)));

        // Then
        assertEquals(DelegationStatus.TRUNCATED, outcomeAt(0));
        assertEquals(DelegationStatus.CANCELLED, outcomeAt(1));
        // 「被拦下」在插件侧就是「根本没开始」，与委派侧的 REJECTED 是同一件事
        assertEquals(DelegationStatus.REJECTED, outcomeAt(2));
        assertEquals(DelegationStatus.FAILED, outcomeAt(3));
    }

    @Test
    @DisplayName("每一步的推进不外泄：它量大，而消费方用不到")
    void step_shouldNotBeRepublished() {
        // Given
        bridge.start();

        // When
        for (int i = 0; i < 100; i++) {
            bus.publish(AgentRunEvent.step(snapshot(AgentRunStatus.RUNNING, i, i), "轮 " + i));
        }

        // Then
        assertTrue(published.isEmpty(), "STEP 不该进通知面");
    }

    @Test
    @DisplayName("未启动时不投递：没订阅就等于不存在")
    void withoutStart_shouldPublishNothing() {
        // When
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));

        // Then
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("重复 start 幂等：同一条事件不会被投递两遍")
    void start_shouldBeIdempotent() {
        // Given
        bridge.start();
        bridge.start();

        // When
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));

        // Then
        assertEquals(1, published.size());
    }

    @Test
    @DisplayName("close 之后不再投递；重复 close 安全")
    void close_shouldUnsubscribe() {
        // Given
        bridge.start();
        bridge.close();
        bridge.close();

        // When
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));

        // Then
        assertTrue(published.isEmpty());
        // 再次 start 可以重新订阅（与「幂等」不冲突：那说的是未关闭时重复调用）
        bridge.start();
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));
        assertEquals(1, published.size());
    }

    @Test
    @DisplayName("不补发历史：订阅者只看得到订阅之后发生的事")
    void start_shouldNotReplayPastEvents() {
        // Given：桥启动之前已经发生过一次开始与一次结束
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));
        bus.publish(AgentRunEvent.finished(snapshot(AgentRunStatus.DONE, 1, 1L)));

        // When：此刻才启动观测
        bridge.start();
        bus.publish(AgentRunEvent.started(snapshot(AgentRunStatus.RUNNING, 0, 0L)));

        // Then：只有订阅之后的那一条；消费方因此必须靠终态自愈，而不是指望补发
        assertEquals(1, published.size());
        assertEquals("run-1", ((AgentRunProgressEvent) published.get(0)).getRunId());
    }

    /**
     * 取第 n 条已发布事件的结局分类。
     *
     * @param index 序号
     * @return 结局分类，可能为 {@code null}
     */
    private DelegationStatus outcomeAt(int index) {
        return ((AgentRunProgressEvent) published.get(index)).getOutcome();
    }

    /**
     * 构造一份 run 快照。
     *
     * @param status      状态
     * @param rounds      轮数
     * @param totalTokens 累计 token
     * @return 快照
     */
    private static AgentRunSnapshot snapshot(AgentRunStatus status, int rounds, long totalTokens) {
        return new AgentRunSnapshot("run-1", null, "run-root", "s-parent", "scout", "s-child", "tc-1",
                status, 1L, 2L, rounds, totalTokens);
    }
}
