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
 * 只读工具集合：给出 PLAN 模式下「这个工具能不能读」的判据，供 {@code PermissionManager} 使用。
 * <p>
 * <b>唯一来源是用户配置</b>：{@code jellyfish.json} 里的
 * {@code plugins.configurations.<pluginId>.readOnlyTools}。用户写了哪些工具名，PLAN 下就只有哪些可用。
 * <p>
 * <b>为什么不再包含「提供方声明的只读」</b>：早先的口径是「工具描述符声明的只读 ∪ 这里的名字」，
 * 那让白名单成了一个<b>只增不减</b>的集合——提供方（插件、脚本作者，MCP 那一侧甚至是不受信的外部进程）
 * 都能往里塞，而用户没有任何手段把它拿出来。PLAN 的准入名单必须是「用户说哪些能用」，
 * 不能是「谁自称只读」，因此 {@code ToolDescriptor.readOnly} 已整个移除，判定只剩这里一个入口。
 * <p>
 * <b>代价是 PLAN 开箱为空</b>：没配任何名字时 PLAN 拒掉全部工具（白名单语义，见
 * {@code PermissionManager} 的判定顺序）。这是刻意的——「用户没表态」与「用户不准」在这里是同一件事，
 * 而默认放行会让 PLAN 形同虚设。
 * <p>
 * 配置可疑（不是数组、含非字符串项）只发 {@link ConfigWarningEvent} 并跳过该项，不中断启动——
 * 与本仓库「配置好坏不阻断启动」的既有口径一致。
 * <p>
 * <b>为什么按快照缓存解析结果</b>：同一条非法配置若每次权限判定都现算，就会在每次判定时重复发告警；
 * {@code PluginRuntimeConfig} 保证同一快照期内返回同一个 configurations 实例，引用比较即可判定
 * 是否需要重算，正常路径下没有重复解析开销。
 *
 * @author zcd
 */
@Singleton
public final class ReadOnlyTools {

    /** 插件运行时装配输入，只读白名单的唯一来源。 */
    private final PluginRuntimeConfig pluginConfig;

    /** 事件发布入口，用于广播配置告警。 */
    private final EventPublisher events;

    /** 上次解析配置所依据的插件配置快照，用于判断是否需要重算。 */
    private Map<String, Map<String, Object>> parsedFrom;

    /** 由 {@link #parsedFrom} 派生的只读工具名。 */
    private volatile Set<String> declaredNames = Collections.emptySet();

    /**
     * 构造只读工具集合。
     * <p>
     * 构造期不解析配置：此时配置多半尚未刷新，读到的必然是空集合。
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
     * @return 在用户配置的白名单里返回 {@code true}；{@code null} 恒为 {@code false}
     */
    public boolean contains(String toolName) {
        return toolName != null && names().contains(toolName);
    }

    /**
     * 获取全部只读工具名（即用户配置的白名单），供权限判定与诊断使用。
     *
     * @return 不可修改集合，未配置任何只读工具时为空集合
     */
    public Set<String> names() {
        Map<String, Map<String, Object>> current = pluginConfig.getPluginConfigurations();
        if (current != parsedFrom) {
            synchronized (this) {
                if (current != parsedFrom) {
                    declaredNames = parse(current, events);
                    parsedFrom = current;
                }
            }
        }
        return declaredNames;
    }

    /**
     * 解析并合并各插件配置段里的只读工具声明。
     * <p>
     * 名字重复天然去重；非法项跳过并发告警。
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
