package zcd.jellyfish.infra.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.notification.CommandExecutedEvent;
import zcd.jellyfish.api.event.notification.CompactionAppliedEvent;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
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
 * <b>断言依赖的每一个计数器都要各自等一遍，不能「等一个总数到位再读分类」</b>。通道默认 2 个核心线程
 * （上限 8）且类注释里明写「允许丢弃、允许乱序」——总数到位只说明最后那条通知<b>开始了</b>处理，
 * 另一个分类项可能还在别的线程手里。这条曾经真的炸过：`PERMISSION_DENIED` 到 2 时读
 * `PERMISSION_ALLOWED` 读到 0（先发的那条 ALLOW 排在另一条线程上），而它平时看不出来——
 * 只有当机器被别的用例压满时才轮到它。<b>修法不是「等更久」，而是不要假设顺序。</b>
 * <p>
 * 因此断言处用有界轮询等待；等待上限远大于线程池调度耗时，正常机器上不会触及，
 * 失败时也只退化为「超时后断言失败」而不是永久挂起。
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
        awaitCounter(MetricNames.COMMAND_UNKNOWN, 1L);
        awaitCounter(MetricNames.COMMAND_ERROR, 1L);
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
        awaitCounter(MetricNames.TOOL_COMPLETED, 1L);
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
        // 这两条断言要各自等：上面那个「2」属于 DENY/ASK，与这条 ALLOW 落在不同的派发任务上
        awaitCounter(MetricNames.PERMISSION_ALLOWED, 1L);
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
        awaitCounter(MetricNames.COMPACTION_COMPRESSED_MESSAGES, 20L);
    }

    @Test
    void start_should_accumulate_llm_token_and_cache_counts() {
        // Given
        subscriber.start();
        channel.start();

        // When：两次调用，其中一次完全未命中缓存
        channel.publish(new LlmCallCompletedEvent("s1", "deepseek", "deepseek-chat",
                new TokenUsageSnapshot(100, 7, 107, 80, 0)));
        channel.publish(new LlmCallCompletedEvent("s1", "deepseek", "deepseek-chat",
                new TokenUsageSnapshot(50, 3, 53, 0, 5)));

        // Then：命中率是「命中 / 总输入」的比值，两个分量都要能读出来（80/150）
        awaitCounter(MetricNames.LLM_CALLS, 2L);
        awaitCounter(MetricNames.LLM_PROMPT_TOKENS, 150L);
        awaitCounter(MetricNames.LLM_CACHE_READ_TOKENS, 80L);
        awaitCounter(MetricNames.LLM_CACHE_WRITE_TOKENS, 5L);
    }

    @Test
    void start_should_count_missing_token_fields_as_zero() {
        // Given：厂商没返回用量
        subscriber.start();
        channel.start();

        // When
        channel.publish(new LlmCallCompletedEvent("s1", null, null,
                new TokenUsageSnapshot(null, null, null, null, null)));

        // Then：调用次数要涨，token 计 0——指标是聚合量，为它引入一套「未知」语义
        // 只会让汇总表多一列谁都算不出来的数
        awaitCounter(MetricNames.LLM_CALLS, 1L);
        awaitCounter(MetricNames.LLM_PROMPT_TOKENS, 0L);
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
        awaitCounter(MetricNames.PLUGIN_STARTED, 1L);
        awaitCounter(MetricNames.PLUGIN_STOPPED, 1L);
        awaitCounter(MetricNames.PLUGIN_FAILED, 1L);
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

        // Then：这里等的值就是终值，而 awaitCounter 断言的是「恰好等于」——
        // 重复订阅会让它涨到 2，因此这一条就足以证明幂等，不必再补一次断言
        awaitCounter(MetricNames.COMMAND_EXECUTED, 1L);
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
        return new PermissionDecidedEvent("coder", "read_file", outcome, "理由", "core", "s1");
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
     * 等待计数器达到期望值，并断言它恰好等于期望值。
     * <p>
     * 「恰好」也是断言的一部分：期望值就是终值，因此等到的值只可能等于它或者更小，
     * 两次读之间的增长说明口径算重了（{@code start_should_be_idempotent} 靠的正是这一点）。
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
