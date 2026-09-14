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
 * 只读工具集合：把各插件在 {@code jellyfish.json} 中声明的只读工具白名单合并成一张全局集合，
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
 * TODO 白名单来自装配期快照：插件热部署后不会刷新，需要随「插件配置热更新」一起处理。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
@Singleton
public final class ReadOnlyTools {

    /** 合并后的只读工具名集合。 */
    private final Set<String> names;

    /**
     * 构造只读工具集合。
     *
     * @param pluginConfig 插件运行时装配输入，提供各插件配置段
     * @param events       事件发布入口，用于广播配置告警
     */
    @Inject
    public ReadOnlyTools(PluginRuntimeConfig pluginConfig, EventPublisher events) {
        Objects.requireNonNull(pluginConfig, "pluginConfig must not be null");
        Objects.requireNonNull(events, "events must not be null");
        this.names = parse(pluginConfig, events);
    }

    /**
     * 判断工具是否为只读工具。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 只读返回 {@code true}；{@code null} 恒为 {@code false}
     */
    public boolean contains(String toolName) {
        return toolName != null && names.contains(toolName);
    }

    /**
     * 获取全部只读工具名，供诊断使用。
     *
     * @return 不可变集合，未配置任何只读工具时为空集合
     */
    public Set<String> names() {
        return names;
    }

    /**
     * 解析并合并各插件的只读工具声明。
     * <p>
     * 重复项天然去重（工具名全局唯一），非法项跳过并发告警。
     *
     * @param pluginConfig 插件运行时装配输入
     * @param events       事件发布入口
     * @return 不可变集合，无声明时为空集合
     */
    private static Set<String> parse(PluginRuntimeConfig pluginConfig, EventPublisher events) {
        Set<String> collected = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> entry : pluginConfig.getPluginConfigurations().entrySet()) {
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
        //      届时需要预留一个保留配置段（例如 plugins.core.readOnlyTools），或把只读性迁到 ToolDescriptor。
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
