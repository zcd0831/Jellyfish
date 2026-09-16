package zcd.jellyfish.infra.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.CommandExecutedEvent;
import zcd.jellyfish.api.event.notification.CompactionAppliedEvent;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetricsSubscriber} 的单元测试：验证内核通知真的被折算成预期指标。
 * <p>
 * 用<b>真实事件通道</b>而不是 mock：这里要验的核心恰恰是「订阅了哪些事件、按什么口径计数」，
 * 把通道 mock 掉就只剩「调过某个方法」。

 * <p>
 * 异步通道意味着计数有延迟，因此断言处用有界轮询等待；等待上限远大于线程池调度耗时，
 * 正常机器上不会触及，失败时也只退化为「超时后断言失败」而不是永久挂起。
 *
 * @author zcd
 */
class MetricsSubscriberTest {

    /** 轮询等待上限（毫秒）。 */
    private static final long AWAIT_TIMEOUT_MILLIS = 3000L;

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MILLIS = 5L;

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 真实事件通道。 */
    private final EventChannel channel = new EventChannel(EventChannelOptions.defaults(), registry);

    /** 指标注册表。 */
    private final MetricsRegistry metrics = new MetricsRegistry();

    /** 被测订阅者。 */
    private final MetricsSubscriber subscriber = new MetricsSubscriber(metrics, channel);

    @AfterEach
    void tearDown() {
        subscriber.close();
        channel.close();
    }

    @Test
    void start_should_count_command_events_by_kind() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(command(CommandResult.Kind.OK));
        channel.publish(command(CommandResult.Kind.UNKNOWN));
        channel.publish(command(CommandResult.Kind.ERROR));

        // Then
        awaitCounter(MetricNames.COMMAND_EXECUTED, 3L);
        assertEquals(1L, counter(MetricNames.COMMAND_UNKNOWN));
        assertEquals(1L, counter(MetricNames.COMMAND_ERROR));
    }

    @Test
    void start_should_count_tool_outcomes() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(new ToolCallStartedEvent("call-1", "read_file", "s1"));
        channel.publish(new ToolCallCompletedEvent("call-1", "read_file", true, 3L, null, "s1"));
        channel.publish(new ToolCallStartedEvent("call-2", "edit_file", "s1"));
        channel.publish(new ToolCallCompletedEvent("call-2", "edit_file", false, 5L, "拒绝", "s1"));

        // Then
        awaitCounter(MetricNames.TOOL_STARTED, 2L);
        awaitCounter(MetricNames.TOOL_FAILED, 1L);
        assertEquals(1L, counter(MetricNames.TOOL_COMPLETED));
    }

    @Test
    void start_should_count_permission_outcomes_by_fail_closed_rule() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(permission(PermissionDecision.Outcome.ALLOW));
        channel.publish(permission(PermissionDecision.Outcome.DENY));
        // ASK 不该出现在事件里，但真出现时也按拒绝计——与 fail-closed 口径一致
        channel.publish(permission(PermissionDecision.Outcome.ASK));

        // Then
        awaitCounter(MetricNames.PERMISSION_DENIED, 2L);
        assertEquals(1L, counter(MetricNames.PERMISSION_ALLOWED));
    }

    @Test
    void start_should_count_session_lifecycle() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(new SessionCreatedEvent("coder", "s1"));
        channel.publish(new SessionClosedEvent("s1", "coder", 4));

        // Then
        awaitCounter(MetricNames.SESSION_CREATED, 1L);
        awaitCounter(MetricNames.SESSION_CLOSED, 1L);
    }

    @Test
    void start_should_accumulate_compaction_counts() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(new CompactionAppliedEvent("s1", "m-1", 12, 3, 80));
        channel.publish(new CompactionAppliedEvent("s1", "m-9", 8, 0, 60));

        // Then
        awaitCounter(MetricNames.COMPACTION_APPLIED, 2L);
        awaitCounter(MetricNames.COMPACTION_DROPPED_MESSAGES, 3L);
        assertEquals(20L, counter(MetricNames.COMPACTION_COMPRESSED_MESSAGES));
    }

    @Test
    void start_should_count_config_and_plugin_events() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(new ConfigWarningEvent("model", "缺失"));
        channel.publish(new ConfigReloadedEvent(new LinkedHashSet<String>(Arrays.asList("a", "b")), 7L));
        channel.publish(new PluginStateChangedEvent("a", "STARTED"));
        channel.publish(new PluginStateChangedEvent("a", "STOPPED"));
        channel.publish(new PluginStateChangedEvent("a", "FAILED"));

        // Then
        awaitCounter(MetricNames.CONFIG_WARNINGS, 1L);
        awaitCounter(MetricNames.CONFIG_RELOADS, 1L);
        awaitCounter(MetricNames.CONFIG_RELOAD_RESTARTED_PLUGINS, 2L);
        awaitCounter(MetricNames.PLUGIN_STATE_CHANGES, 3L);
        assertEquals(1L, counter(MetricNames.PLUGIN_STARTED));
        assertEquals(1L, counter(MetricNames.PLUGIN_STOPPED));
        assertEquals(1L, counter(MetricNames.PLUGIN_FAILED));
    }

    @Test
    void start_should_expose_event_channel_stats_as_gauges() {
        // Given
        subscriber.start();
        channel.start();

        // When
        channel.publish(command(CommandResult.Kind.OK));
        awaitCounter(MetricNames.COMMAND_EXECUTED, 1L);

        // Then：仪表现读通道统计，至少已发布数不为零
        Map<String, Long> gauges = metrics.snapshot().getGauges();
        assertTrue(gauges.containsKey(MetricNames.EVENT_PUBLISHED));
        assertTrue(gauges.get(MetricNames.EVENT_PUBLISHED) >= 1L);
    }

    @Test
    void start_should_be_idempotent() {
        // Given：重复启动不该把同一事件算两遍（订阅只注册一次）
        subscriber.start();
        subscriber.start();
        channel.start();

        // When
        channel.publish(command(CommandResult.Kind.OK));

        // Then
        awaitCounter(MetricNames.COMMAND_EXECUTED, 1L);
        assertEquals(1L, counter(MetricNames.COMMAND_EXECUTED));
    }

    @Test
    void close_should_remove_subscriptions() throws InterruptedException {
        // Given
        subscriber.start();
        channel.start();
        subscriber.close();

        // When：退订之后再发，计数不应增长
        channel.publish(command(CommandResult.Kind.OK));
        Thread.sleep(100L);

        // Then
        assertNull(metrics.snapshot().getCounters().get(MetricNames.COMMAND_EXECUTED));
    }

    /**
     * 构造一条命令审计事件。
     *
     * @param kind 结果三态
     * @return 事件
     */
    private static CommandExecutedEvent command(CommandResult.Kind kind) {
        return new CommandExecutedEvent("/x", "x", "x", kind, "core", 1L, "s1");
    }

    /**
     * 构造一条权限判定事件。
     *
     * @param outcome 判定结论
     * @return 事件
     */
    private static PermissionDecidedEvent permission(PermissionDecision.Outcome outcome) {
        return new PermissionDecidedEvent("coder", "read_file", PermissionMode.NORMAL, outcome, "理由", "core", "s1");
    }

    /**
     * 读取计数器当前值。
     *
     * @param name 指标名
     * @return 计数值；不存在时为 0
     */
    private long counter(String name) {
        Long value = metrics.snapshot().getCounters().get(name);
        return value == null ? 0L : value;
    }

    /**
     * 等待计数器达到期望值。
     *
     * @param name     指标名
     * @param expected 期望值
     */
    private void awaitCounter(String name, long expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (counter(name) < expected && System.currentTimeMillis() < deadline) {
            sleep();
        }
        assertEquals(expected, counter(name), "指标未在超时内达到期望值: " + name);
    }

    /**
     * 短暂休眠，等待异步派发。
     */
    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
