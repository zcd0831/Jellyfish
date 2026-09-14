package zcd.jellyfish.infra.permission;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 只读工具集合：把各插件在 {@code jellyfish.json} 里声明的只读工具白名单合并成一张全局集合，
 * 供 {@code PermissionManager} 在 PLAN 模式下判定「这个工具能不能读」。
 * <p>
 * <b>为什么不按「工具 → 插件」归因</b>：工具名全局唯一这一点已由「{@code ToolCallRequest} + 同键唯一注册」
 * 保证，因此「哪个插件声明的只读」不影响判定结果，直接合并成扁平集合即可——也就不需要给扩展层
 * 新增 owner 归因能力。
 * <p>
 * <b>只读性为什么由插件声明</b>：只有工具提供方知道自己的工具会不会产生副作用，所以白名单写在插件
 * 自己那一节配置里，用户还能在项目级配置中覆盖。
 * <p>
 * 配置可疑（不是数组、含非字符串项）只发 {@link ConfigWarningEvent} 并跳过该项，不中断启动——
 * 与本仓库「配置好坏不阻断启动」的既有口径一致。
 * <p>
 * <b>为什么按快照引用缓存而不是构造期解析一次</b>：本对象由 Dagger 懒加载，构造时机不确定，
 * 完全可能早于 {@code AgentHarness.bootstrap()} 里的配置刷新；只解析一次会永久读到空集合。
 * 因此改为「读的时候比对快照引用，换过就重算」——{@code PluginRuntimeConfig} 保证同一快照期内
 * 返回同一个 configurations 实例，引用比较即可判定，正常路径下没有重复解析开销。
 * <p>
 * TODO 白名单只跟随插件配置快照：插件<b>热部署</b>不会改动配置段，因此那份白名单不会随之刷新，
 *      需要随「插件配置热更新」一起处理。
 *
 * @author zcd
 */
@Singleton
public final class ReadOnlyTools {

    /** 插件运行时装配输入，白名单的来源。 */
    private final PluginRuntimeConfig pluginConfig;

    /** 事件发布入口，用于广播配置告警。 */
    private final EventPublisher events;

    /** 上次解析所依据的插件配置快照，用于判断是否需要重算。 */
    private Map<String, Map<String, Object>> parsedFrom;

    /** 由 {@link #parsedFrom} 派生的只读工具名集合。 */
    private volatile Set<String> names = Collections.emptySet();

    /**
     * 构造只读工具集合。
     * <p>
     * 构造期不解析：此时配置多半尚未刷新，读到的必然是空集合。
     *
     * @param pluginConfig 插件运行时装配输入，提供各插件配置段
     * @param events       事件发布入口，用于广播配置告警
     */
    @Inject
    public ReadOnlyTools(PluginRuntimeConfig pluginConfig, EventPublisher events) {
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "pluginConfig must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * 判断工具是否为只读工具。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 只读返回 {@code true}；{@code null} 恒为 {@code false}
     */
    public boolean contains(String toolName) {
        return toolName != null && names().contains(toolName);
    }

    /**
     * 获取全部只读工具名，供诊断使用。
     *
     * @return 不可修改集合，未配置任何只读工具时为空集合
     */
    public Set<String> names() {
        Map<String, Map<String, Object>> current = pluginConfig.getPluginConfigurations();
        if (current != parsedFrom) {
            synchronized (this) {
                if (current != parsedFrom) {
                    names = parse(current, events);
                    parsedFrom = current;
                }
            }
        }
        return names;
    }

    /**
     * 解析并合并各插件的只读工具声明。
     * <p>
     * 重复项天然去重（工具名全局唯一），非法项跳过并发告警。
     *
     * @param configurations pluginId → 配置段
     * @param events         事件发布入口
     * @return 不可变集合，无声明时为空集合
     */
    private static Set<String> parse(Map<String, Map<String, Object>> configurations, EventPublisher events) {
        Set<String> collected = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : configurations.entrySet()) {
            Map<String, Object> configuration = entry.getValue();
            if (configuration == null) {
                continue;
            }
            Object declared = configuration.get(PermissionSettings.READ_ONLY_TOOLS);
            if (declared == null) {
                continue;
            }
            if (!(declared instanceof Collection)) {
                warn(events, entry.getKey(), "readOnlyTools 应为工具名数组，实际为 "
                        + declared.getClass().getName());
                continue;
            }
            for (Object item : (Collection<?>) declared) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    collected.add((String) item);
                } else {
                    warn(events, entry.getKey(), "readOnlyTools 含非空字符串以外的项，已跳过: " + item);
                }
            }
        }
        // TODO 内核将来自己注册的工具没有插件配置段，在 PLAN 模式下无法声明只读，会被一律拒绝。
        //      触发点是「LLM 可写的 todo_write 核心工具」那一轮（计划模式下它必须被判为只读），
        //      届时需要预留一个保留配置段（例如 plugins.configurations.core.readOnlyTools），
        //      或把只读性迁到 ToolDescriptor。
        return Collections.unmodifiableSet(collected);
    }

    /**
     * 广播一条配置告警。
     *
     * @param events   事件发布入口
     * @param pluginId 插件标识，作为配置来源
     * @param message  告警描述
     */
    private static void warn(EventPublisher events, String pluginId, String message) {
        events.publish(new ConfigWarningEvent(pluginId, message));
    }
}
