package zcd.jellyfish.script.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import zcd.jellyfish.api.event.JellyfishEvent;
import zcd.jellyfish.api.event.notification.AgentsLoadedEvent;
import zcd.jellyfish.api.event.notification.CommandExecutedEvent;
import zcd.jellyfish.api.event.notification.CompactionAppliedEvent;
import zcd.jellyfish.api.event.notification.ConfigReloadedEvent;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.event.notification.ModelsLoadedEvent;
import zcd.jellyfish.api.event.notification.PermissionDecidedEvent;
import zcd.jellyfish.api.event.notification.PluginNotificationEvent;
import zcd.jellyfish.api.event.notification.PluginStateChangedEvent;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.event.notification.SessionCreatedEvent;
import zcd.jellyfish.api.event.notification.SessionMessageAppendedEvent;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;

/**
 * 可订阅事件目录：脚本能观察到的内核事件清单，以及每类事件下发给脚本时的字段投影。
 * <p>
 * 三点是刻意的设计而不是偷懒：
 * <ol>
 *   <li><b>清单硬编码在这里，不走配置</b>：事件类本来随内核版本增删，配置化只会带来两份清单的漂移，
 *       而漂移的表现是「脚本声明订阅了一个再也不存在的事件」——这类问题在配置文件里看不出来。</li>
 *   <li><b>白名单而非黑名单</b>：内核以后新增的事件不会自动对脚本开放。事件是内核内部事实的外泄，
 *       开放与否应当是显式决定。</li>
 *   <li><b>只下发标量字段</b>：不下发嵌套快照结构（如整个 {@code SessionSnapshot}）。
 *       下发了就等于把内核的内部结构变成脚本的对外契约，此后重构会一路碎到脚本里；
 *       而脚本真正需要的是「发生了什么、哪个会话、什么名字」这类标量。</li>
 * </ol>
 * 事件名取类名（{@code SessionCreatedEvent}），与 manifest 的 {@code events} 声明的名字逐字一致。
 *
 * @author zcd
 */
public final class ScriptEventCatalog {

    /** 投影函数：把一个内核事件压成脚本看得懂的字段表。 */
    @FunctionalInterface
    public interface Projection {

        /**
         * 计算业务字段。
         *
         * @param event 事件（调用方保证类型与注册时一致）
         * @return 业务字段映射；公共三件套由 {@link ScriptEventCatalog#project(JellyfishEvent)} 补齐
         */
        Map<String, Object> project(JellyfishEvent event);
    }

    /** 事件名字段：投影结果里始终带着事件名，让脚本不必依赖「我是谁被谁调」的隐式知识。 */
    public static final String FIELD_EVENT = "event";

    /** 目录：事件类 → 投影。顺序即日志与文档里的展示顺序。 */
    private static final Map<Class<? extends JellyfishEvent>, Projection> ENTRIES =
            new LinkedHashMap<Class<? extends JellyfishEvent>, Projection>();

    /** 事件名 → 事件类，供按名字查询。 */
    private static final Map<String, Class<? extends JellyfishEvent>> BY_NAME =
            new LinkedHashMap<String, Class<? extends JellyfishEvent>>();

    static {
        observe(SessionCreatedEvent.class, event ->
                fields("agentId", ((SessionCreatedEvent) event).getAgentId()));
        observe(SessionClosedEvent.class, event ->
                fields("agentId", ((SessionClosedEvent) event).getAgentId(),
                        "messageCount", Integer.valueOf(((SessionClosedEvent) event).getMessageCount())));
        observe(SessionMessageAppendedEvent.class, event ->
                fields("messageId", ((SessionMessageAppendedEvent) event).getMessageId(),
                        "role", ((SessionMessageAppendedEvent) event).getRole()));
        observe(ToolCallStartedEvent.class, event ->
                fields("toolCallId", ((ToolCallStartedEvent) event).getToolCallId(),
                        "toolName", ((ToolCallStartedEvent) event).getToolName()));
        observe(ToolCallCompletedEvent.class, event -> {
            ToolCallCompletedEvent typed = (ToolCallCompletedEvent) event;
            return fields("toolCallId", typed.getToolCallId(),
                    "toolName", typed.getToolName(),
                    "success", Boolean.valueOf(typed.isSuccess()),
                    "durationMillis", Long.valueOf(typed.getDurationMillis()),
                    "errorMessage", typed.getErrorMessage());
        });
        observe(CommandExecutedEvent.class, event -> {
            CommandExecutedEvent typed = (CommandExecutedEvent) event;
            return fields("input", typed.getInput(),
                    "name", typed.getName(),
                    "canonicalName", typed.getCanonicalName(),
                    "kind", name(typed.getKind()),
                    "source", typed.getSource(),
                    "durationMillis", Long.valueOf(typed.getDurationMillis()));
        });
        observe(PermissionDecidedEvent.class, event -> {
            PermissionDecidedEvent typed = (PermissionDecidedEvent) event;
            return fields("agentId", typed.getAgentId(),
                    "toolName", typed.getToolName(),
                    "mode", name(typed.getMode()),
                    "outcome", name(typed.getOutcome()),
                    "reason", typed.getReason(),
                    "source", typed.getSource());
        });
        observe(CompactionAppliedEvent.class, event -> {
            CompactionAppliedEvent typed = (CompactionAppliedEvent) event;
            return fields("boundaryMessageId", typed.getBoundaryMessageId(),
                    "compressedCount", Integer.valueOf(typed.getCompressedCount()),
                    "droppedCount", Integer.valueOf(typed.getDroppedCount()),
                    "summaryLength", Integer.valueOf(typed.getSummaryLength()));
        });
        observe(PluginStateChangedEvent.class, event -> {
            PluginStateChangedEvent typed = (PluginStateChangedEvent) event;
            return fields("pluginId", typed.getPluginId(), "state", typed.getState());
        });
        observe(PluginNotificationEvent.class, event -> {
            PluginNotificationEvent typed = (PluginNotificationEvent) event;
            return fields("source", typed.getSource(), "payload", typed.getPayload());
        });
        observe(ConfigWarningEvent.class, event -> {
            ConfigWarningEvent typed = (ConfigWarningEvent) event;
            return fields("source", typed.getSource(), "message", typed.getMessage());
        });
        observe(ConfigReloadedEvent.class, event ->
                fields("restartedPluginIds", sorted(((ConfigReloadedEvent) event).getRestartedPluginIds()),
                        "durationMillis", Long.valueOf(((ConfigReloadedEvent) event).getDurationMillis())));
        observe(AgentsLoadedEvent.class, event ->
                fields("defaultAgentId", ((AgentsLoadedEvent) event).getDefaultAgentId(),
                        "agentIds", sorted(((AgentsLoadedEvent) event).getAgentIds())));
        observe(ModelsLoadedEvent.class, event ->
                fields("defaultProvider", ((ModelsLoadedEvent) event).getDefaultProvider(),
                        "defaultModel", ((ModelsLoadedEvent) event).getDefaultModel(),
                        "providerNames", sorted(((ModelsLoadedEvent) event).getProviderNames())));
        observe(UiInvalidatedEvent.class, event -> fields());
    }

    /**
     * 私有构造器：纯常量与静态方法。
     */
    private ScriptEventCatalog() {
    }

    /**
     * 登记一个可订阅事件。
     *
     * @param type       事件类型
     * @param projection 业务字段投影
     * @param <E>        事件类型参数
     */
    private static <E extends JellyfishEvent> void observe(Class<E> type, Projection projection) {
        ENTRIES.put(type, projection);
        Class<? extends JellyfishEvent> previous = BY_NAME.put(type.getSimpleName(), type);
        if (previous != null) {
            throw new IllegalStateException("事件名重复: " + type.getSimpleName());
        }
    }

    /**
     * 全部可订阅事件名，按登记顺序。
     *
     * @return 不可变集合
     */
    public static Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(BY_NAME.keySet()));
    }

    /**
     * 判断事件名是否可订阅。
     *
     * @param name 事件名（类名）
     * @return 可订阅返回 {@code true}
     */
    public static boolean isObservable(String name) {
        return name != null && BY_NAME.containsKey(name);
    }

    /**
     * 按名字取事件类型。
     *
     * @param name 事件名（类名）
     * @return 事件类型；不可订阅时返回 {@code null}
     */
    public static Class<? extends JellyfishEvent> typeOf(String name) {
        return BY_NAME.get(name);
    }

    /**
     * 事件类型 → 事件名。
     *
     * @param type 事件类型
     * @return 事件名；不在目录里时返回 {@code null}
     */
    public static String nameOf(Class<? extends JellyfishEvent> type) {
        return ENTRIES.containsKey(type) ? type.getSimpleName() : null;
    }

    /**
     * 判断事件类型是否可订阅。
     *
     * @param type 事件类型
     * @return 可订阅返回 {@code true}
     */
    public static boolean isObservable(Class<? extends JellyfishEvent> type) {
        return ENTRIES.containsKey(type);
    }

    /**
     * 把事件投影成下发给脚本的字段表。
     * <p>
     * 不在目录里的事件也能投影（只有公共三件套）：调用方是事件通道的监听线程，
     * 让它在这里抛异常等于让一个「订阅了我们没登记的类型」的事件炸掉整个派发。
     *
     * @param event 事件
     * @return 字段表；顺序固定为公共三件套在前、业务字段在后
     */
    public static Map<String, Object> project(JellyfishEvent event) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(FIELD_EVENT, event.getClass().getSimpleName());
        values.put("eventId", event.getEventId());
        values.put("occurredAt", Long.valueOf(event.getOccurredAt()));
        if (event.getSessionId() != null) {
            values.put("sessionId", event.getSessionId());
        }
        Projection projection = ENTRIES.get(event.getClass());
        if (projection != null) {
            values.putAll(projection.project(event));
        }
        return values;
    }

    /**
     * 组装业务字段表：{@code null} 表示「没有这个字段」，不写成 JSON 的 {@code null}。
     * <p>
     * 两者对脚本是不同的语义：字段缺失是「这次事件没有这个信息」，写成 {@code null} 会让脚本
     * 以为内核明确说了「它是空的」，于是写出 {@code if event.get("x") is None} 这种永远为真的分支。
     *
     * @param pairs 字段名与值交替出现
     * @return 字段表
     */
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                values.put(String.valueOf(pairs[i]), value);
            }
        }
        return values;
    }

    /**
     * 枚举取值：取名字而不是 {@code toString()}，让脚本拿到的是稳定标识。
     *
     * @param value 枚举值，可为 {@code null}
     * @return 枚举名；入参为 {@code null} 时返回 {@code null}
     */
    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * 集合字段：排序后下发。
     * <p>
     * 不排序的话，同一个事件两次投影出来的顺序取决于 {@code HashSet} 的迭代顺序，
     * 而那种顺序在脚本看来是随机的、且不保证跨进程稳定。
     *
     * @param values 集合，可为 {@code null}
     * @return 排序后的列表
     */
    private static List<String> sorted(Set<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(new TreeSet<String>(values));
    }
}
