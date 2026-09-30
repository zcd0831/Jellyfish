package zcd.jellyfish.infra.metrics;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import zcd.jellyfish.api.event.Subscription;
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
import zcd.jellyfish.api.event.notification.LlmCallCompletedEvent;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;
import zcd.jellyfish.infra.event.EventChannel;

/**
 * 指标订阅者：把内核通知折算成指标，是「可观测性」这条边上的唯一写入方。
 * <p>
 * <b>纯订阅者</b>：只读事件、只写指标，<b>不发布任何事件</b>——否则会形成
 * 「事件 → 指标 → 事件」的自激循环，而指标本身也不该成为被观测对象的负担。
 * <p>
 * <b>只订阅事件通道，不碰同步扩展点</b>：这是「能否丢弃」这条判据的自然结果——
 * 少记一个计数不影响任何业务结果，因此它落在 best-effort 的异步侧；
 * 审计级可靠性由调用点自己负责，不在这里补。
 * <p>
 * <b>口径说明</b>：{@code tool.failed} 统计的是「工具结果标记为失败」（含权限拒绝与工具异常），
 * 与 ReAct 回灌给模型的 tool 结果消息一一对应；{@code permission.denied} 把未放行的一切
 * （{@code DENY} 与不该出现在事件里的 {@code ASK}）都算作拒绝，与权限模块 fail-closed 的口径一致。
 *
 * @author zcd
 */
@Singleton
public final class MetricsSubscriber implements AutoCloseable {

    /** 订阅来源标识：统一用它，便于整体回收。 */
    private static final String OWNER = "metrics";

    /** 指标注册表。 */
    private final MetricsRegistry registry;

    /** 事件通道：唯一的数据来源。 */
    private final EventChannel events;

    /** 已建立的订阅句柄，关闭时解除。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /** 是否已启动：{@link #start()} 与 {@link #close()} 都幂等。 */
    private boolean started;

    /**
     * 构造订阅者。
     *
     * @param registry 指标注册表，不可为 {@code null}
     * @param events   事件通道，不可为 {@code null}
     */
    @Inject
    public MetricsSubscriber(MetricsRegistry registry, EventChannel events) {
        this.registry = registry;
        this.events = events;
    }

    /**
     * 开始采集：注册仪表并订阅内核通知。幂等。
     * <p>
     * 必须在 {@code eventChannel.start()} 之后、配置加载之前调用：之后才注册得上订阅，
     * 而配置加载阶段发出的告警要能被计数。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        registerGauges();
        subscriptions.add(events.subscribe(OWNER, CommandExecutedEvent.class, this::onCommand));
        subscriptions.add(events.subscribe(OWNER, ToolCallStartedEvent.class,
                event -> registry.increment(MetricNames.TOOL_STARTED)));
        subscriptions.add(events.subscribe(OWNER, ToolCallCompletedEvent.class, this::onToolCompleted));
        subscriptions.add(events.subscribe(OWNER, PermissionDecidedEvent.class, this::onPermission));
        subscriptions.add(events.subscribe(OWNER, SessionCreatedEvent.class,
                event -> registry.increment(MetricNames.SESSION_CREATED)));
        subscriptions.add(events.subscribe(OWNER, SessionClosedEvent.class,
                event -> registry.increment(MetricNames.SESSION_CLOSED)));
        subscriptions.add(events.subscribe(OWNER, CompactionAppliedEvent.class, this::onCompaction));
        subscriptions.add(events.subscribe(OWNER, ConfigWarningEvent.class,
                event -> registry.increment(MetricNames.CONFIG_WARNINGS)));
        subscriptions.add(events.subscribe(OWNER, ConfigReloadedEvent.class, this::onConfigReloaded));
        subscriptions.add(events.subscribe(OWNER, PluginStateChangedEvent.class, this::onPluginState));
        subscriptions.add(events.subscribe(OWNER, LlmCallCompletedEvent.class, this::onLlmCall));
    }

    /**
     * 停止采集：解除全部订阅。幂等。
     * <p>
     * 只解除自己那一份订阅，不碰注册表里别人的内容。
     */
    @Override
    public synchronized void close() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        started = false;
    }

    /**
     * 把事件通道自身的统计注册成仪表。
     * <p>
     * 这些值直接取自 {@code EventChannelStats}，不经过事件通道——这正是「自身指标不上报自身」的落点。
     */
    private void registerGauges() {
        registry.gauge(MetricNames.EVENT_PUBLISHED, () -> events.stats().getPublishedEvents());
        registry.gauge(MetricNames.EVENT_DROPPED, () -> events.stats().getDroppedEvents());
        registry.gauge(MetricNames.EVENT_UNMATCHED, () -> events.stats().getUnmatchedNotifications());
        registry.gauge(MetricNames.EVENT_SUBSCRIBER_ERRORS, () -> events.stats().getSubscriberErrors());
        registry.gauge(MetricNames.EVENT_ACTIVE_THREADS, () -> events.stats().getActiveThreads());
        registry.gauge(MetricNames.EVENT_QUEUE_SIZE, () -> events.stats().getQueueSize());
    }

    /**
     * 折算命令审计事件。
     *
     * @param event 命令审计事件
     */
    private void onCommand(CommandExecutedEvent event) {
        registry.increment(MetricNames.COMMAND_EXECUTED);
        CommandResult.Kind kind = event.getKind();
        if (kind == CommandResult.Kind.ERROR) {
            registry.increment(MetricNames.COMMAND_ERROR);
        } else if (kind == CommandResult.Kind.UNKNOWN) {
            registry.increment(MetricNames.COMMAND_UNKNOWN);
        }
    }

    /**
     * 折算工具完成事件。
     *
     * @param event 工具完成事件
     */
    private void onToolCompleted(ToolCallCompletedEvent event) {
        if (event.isSuccess()) {
            registry.increment(MetricNames.TOOL_COMPLETED);
        } else {
            registry.increment(MetricNames.TOOL_FAILED);
        }
    }

    /**
     * 折算权限判定事件：只有明确放行才算放行，其余（含不该出现的 ASK）一律计为拒绝。
     *
     * @param event 权限判定事件
     */
    private void onPermission(PermissionDecidedEvent event) {
        if (event.getOutcome() == PermissionDecision.Outcome.ALLOW) {
            registry.increment(MetricNames.PERMISSION_ALLOWED);
        } else {
            registry.increment(MetricNames.PERMISSION_DENIED);
        }
    }

    /**
     * 折算压缩应用事件。
     *
     * @param event 压缩应用事件
     */
    private void onCompaction(CompactionAppliedEvent event) {
        registry.increment(MetricNames.COMPACTION_APPLIED);
        registry.add(MetricNames.COMPACTION_COMPRESSED_MESSAGES, event.getCompressedCount());
        registry.add(MetricNames.COMPACTION_DROPPED_MESSAGES, event.getDroppedCount());
    }

    /**
     * 折算模型调用完成事件：把 token 与缓存计数累加进指标。
     * <p>
     * <b>为什么缓存命中数要单独累计</b>：命中率是一个「分子 / 分母」的比值——分子（命中）与
     * 分母（总输入）在这里就是那个比值的两个分量。此前它们只在会话状态里（{@code /usage} 可读），
     * 进程级完全看不到，于是「这台机器一共命中了多少缓存」无从算起。
     * <p>
     * <b>缺失的字段按 0 计</b>：指标是聚合量，为它引入一套「未知」语义只会让汇总表多一列谁都算不出来的数。
     *
     * @param event 模型调用完成事件
     */
    private void onLlmCall(LlmCallCompletedEvent event) {
        TokenUsageSnapshot usage = event.getUsage();
        registry.increment(MetricNames.LLM_CALLS);
        registry.add(MetricNames.LLM_PROMPT_TOKENS, countOf(usage.getPromptTokens()));
        registry.add(MetricNames.LLM_CACHE_READ_TOKENS, countOf(usage.getCacheReadTokens()));
        registry.add(MetricNames.LLM_CACHE_WRITE_TOKENS, countOf(usage.getCacheWriteTokens()));
    }

    /**
     * 把可空计数按 0 处理。
     *
     * @param value 计数，可为 {@code null}
     * @return 非空计数
     */
    private static long countOf(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * 折算配置重载事件。
     *
     * @param event 配置重载事件
     */
    private void onConfigReloaded(ConfigReloadedEvent event) {
        registry.increment(MetricNames.CONFIG_RELOADS);
        registry.add(MetricNames.CONFIG_RELOAD_RESTARTED_PLUGINS, event.getRestartedPluginIds().size());
    }

    /**
     * 折算插件状态变更事件。
     *
     * @param event 插件状态变更事件
     */
    private void onPluginState(PluginStateChangedEvent event) {
        registry.increment(MetricNames.PLUGIN_STATE_CHANGES);
        String state = event.getState();
        if ("STARTED".equals(state)) {
            registry.increment(MetricNames.PLUGIN_STARTED);
        } else if ("STOPPED".equals(state)) {
            registry.increment(MetricNames.PLUGIN_STOPPED);
        } else if ("FAILED".equals(state)) {
            registry.increment(MetricNames.PLUGIN_FAILED);
        }
    }
}
