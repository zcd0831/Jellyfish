package zcd.jellyfish.script.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件桥接的单元测试。
 * <p>
 * 用假的目标与假的插件上下文，因此可以精确地构造那些真实环境里很难凑出来的场面：
 * 队列被打满、网关在推送前刚好退出、脚本发布了一个不可发布的事件。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("脚本事件桥接")
class ScriptEventBridgeTest {

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    /** 推送目标。 */
    @Mock
    private ScriptEventTarget target;

    /** 被测桥接。 */
    private ScriptEventBridge bridge;

    /**
     * 关闭桥接，确保推送线程不跨用例存活。
     */
    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
    }

    @Test
    @DisplayName("start 应为每个可订阅事件各订阅一次")
    void start_should_subscribeEveryObservableEvent() {
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());

        bridge.start();

        assertEquals(ScriptEventCatalog.names().size(), bridge.observableNames().size());
        verify(context, atLeastOnce()).observe(any(), any(Consumer.class));
    }

    @Test
    @DisplayName("events.allow 只应额外收窄，不应扩展")
    void start_should_narrow_when_allowedGiven() {
        bridge = new ScriptEventBridge(context, "Test", target,
                Arrays.asList("SessionCreatedEvent", "NoSuchEvent"));

        bridge.start();

        assertEquals(Collections.singleton("SessionCreatedEvent"), bridge.observableNames());
    }

    @Test
    @DisplayName("网关未运行时事件应被丢弃且不推送")
    void enqueue_should_drop_when_gatewayNotRunning() throws Exception {
        when(target.isRunning()).thenReturn(false);
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());
        bridge.start();
        Consumer<JellyfishEvent> listener = capturedListener();

        listener.accept(new SessionCreatedEvent("coder", "s-1"));
        Thread.sleep(200L);

        assertEquals(0L, bridge.pushedCount());
        assertEquals(1L, bridge.droppedOfflineCount());
    }

    @Test
    @DisplayName("网关运行时事件应被投影并推送")
    void enqueue_should_pushProjectedEvent_when_gatewayRunning() throws Exception {
        when(target.isRunning()).thenReturn(true);
        CountDownLatch pushed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            pushed.countDown();
            return null;
        }).when(target).notifyEvent(eq("SessionCreatedEvent"), any());
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());
        bridge.start();
        Consumer<JellyfishEvent> listener = capturedListener();

        listener.accept(new SessionCreatedEvent("coder", "s-1"));

        assertTrue(pushed.await(5, TimeUnit.SECONDS), "事件未被推送");
        assertEquals(1L, bridge.pushedCount());
        assertEquals(0L, bridge.droppedCount());
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> payload =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(target).notifyEvent(eq("SessionCreatedEvent"), payload.capture());
        assertEquals("coder", payload.getValue().get("agentId").asText());
        assertEquals("s-1", payload.getValue().get("sessionId").asText());
    }

    @Test
    @DisplayName("推送失败应记入丢弃而不是让推送线程退出")
    void drain_should_countAndSurvive_when_pushFails() throws Exception {
        when(target.isRunning()).thenReturn(true);
        org.mockito.Mockito.doThrow(new JellyfishException("进程没了"))
                .when(target).notifyEvent(any(), any());
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());
        bridge.start();
        Consumer<JellyfishEvent> listener = capturedListener();

        listener.accept(new SessionCreatedEvent("coder", "s-1"));
        listener.accept(new SessionCreatedEvent("coder", "s-2"));
        long deadline = System.currentTimeMillis() + 5000L;
        while (bridge.droppedOfflineCount() < 2L && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }

        assertEquals(2L, bridge.droppedOfflineCount());
        assertEquals(0L, bridge.pushedCount());
    }

    @Test
    @DisplayName("队列满时应丢弃并计数，而不是阻塞通知线程")
    void enqueue_should_dropAndCount_when_queueFull() throws Exception {
        when(target.isRunning()).thenReturn(true);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(target).notifyEvent(any(), any());
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());
        bridge.start();
        Consumer<JellyfishEvent> listener = capturedListener();

        for (int i = 0; i < 400; i++) {
            listener.accept(new SessionCreatedEvent("coder", "s-" + i));
        }

        // 通知线程一次也没被卡住：全部入队或入队失败，没有等待
        assertTrue(bridge.droppedFullCount() > 0L, "队列未被填满，用例失去意义");
        release.countDown();
    }

    @Test
    @DisplayName("受理发布应返回事件标识并把事件送进事件通道")
    void accept_should_emitAndReturnEventId() {
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());

        String eventId = bridge.accept("jira", "ConfigWarningEvent",
                zcd.jellyfish.script.ScriptJson.tree("{\"message\":\"x\",\"sessionId\":\"s-1\"}"));

        assertNotNull(eventId);
        ArgumentCaptor<JellyfishEvent> emitted = ArgumentCaptor.forClass(JellyfishEvent.class);
        verify(context).emit(emitted.capture());
        assertEquals(eventId, emitted.getValue().getEventId());
        assertEquals("s-1", emitted.getValue().getSessionId());
        assertEquals(1L, bridge.emittedCount());
        assertEquals(0L, bridge.rejectedCount());
    }

    @Test
    @DisplayName("发布内核语义事件应抛异常并计入拒绝")
    void accept_should_throwAndCount_when_eventNotEmittable() {
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());

        assertThrows(JellyfishException.class, () -> bridge.accept("jira", "SessionCreatedEvent",
                zcd.jellyfish.script.ScriptJson.tree("{\"agentId\":\"fake\"}")));

        assertEquals(0L, bridge.emittedCount());
        assertEquals(1L, bridge.rejectedCount());
    }

    @Test
    @DisplayName("事件通道发布失败时应把失败原因回给脚本侧")
    void accept_should_reportFailure_when_emitThrows() {
        org.mockito.Mockito.doThrow(new IllegalStateException("通道已关闭")).when(context).emit(any());
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> bridge.accept("jira", "ConfigWarningEvent",
                        zcd.jellyfish.script.ScriptJson.tree("{\"message\":\"x\"}")));

        assertTrue(error.getMessage().contains("发布失败"), error.getMessage());
        assertEquals(1L, bridge.emittedCount());
    }

    @Test
    @DisplayName("close 应退订并停止推送线程")
    void close_should_unsubscribe() {
        Subscription subscription = org.mockito.Mockito.mock(Subscription.class);
        when(context.observe(any(), any(Consumer.class))).thenReturn(subscription);
        bridge = new ScriptEventBridge(context, "Test", target,
                Collections.singletonList("SessionCreatedEvent"));
        bridge.start();
        int subscriptions = bridge.observableNames().size();

        bridge.close();

        verify(subscription, org.mockito.Mockito.times(subscriptions)).close();
        bridge = null;
    }

    @Test
    @DisplayName("摘要应同时报推送、丢弃与发布两侧")
    void describe_should_coverBothDirections() {
        bridge = new ScriptEventBridge(context, "Test", target, Collections.<String>emptyList());
        bridge.accept("jira", "ConfigWarningEvent",
                zcd.jellyfish.script.ScriptJson.tree("{\"message\":\"x\"}"));

        String summary = bridge.describe();

        assertTrue(summary.contains("推送 0"), summary);
        assertTrue(summary.contains("受理 1"), summary);
        assertTrue(summary.contains("拒绝 0"), summary);
    }

    @Test
    @DisplayName("没有订阅任何事件时摘要仍应可用")
    void describe_should_work_when_nothingObserved() {
        bridge = new ScriptEventBridge(context, "Test", target,
                Collections.singletonList("SessionCreatedEvent"));

        assertTrue(bridge.describe().contains("丢弃 0"), bridge.describe());
        assertEquals(0L, bridge.pushedCount());
    }

    /**
     * 取出桥接注册给事件通道的监听器。
     *
     * @return 被捕获的监听器
     */
    @SuppressWarnings("unchecked")
    private Consumer<JellyfishEvent> capturedListener() {
        ArgumentCaptor<Consumer<JellyfishEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(context, atLeastOnce()).observe(any(), captor.capture());
        List<Consumer<JellyfishEvent>> captured = new CopyOnWriteArrayList<Consumer<JellyfishEvent>>(
                captor.getAllValues());
        // 第一个被订阅的事件是目录里的第一个：本用例只发这个类型的事件
        return captured.get(0);
    }
}
