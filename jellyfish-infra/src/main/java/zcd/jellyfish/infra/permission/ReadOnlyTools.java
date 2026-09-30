package zcd.jellyfish.infra.permission;

import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
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
 * 工具名来自两处，取<b>并集</b>：
 * <ol>
 *     <li><b>工具描述符</b>（权威来源）：{@link ToolDescriptor#isReadOnly()} 由工具提供方在注册时声明。
 *     只有提供方知道自己的工具有没有副作用；描述符随 handler 一起落库，插件下架或热部署时它自动跟着变，
 *     因此无需任何「插件变更通知」这类额外的边；</li>
 *     <li><b>插件配置</b>（用户追加）：{@code jellyfish.json} 的
 *     {@code plugins.configurations.<pluginId>.readOnlyTools}，用于把提供方<b>没标</b>只读的工具
 *     自行纳入 PLAN 白名单。只能追加，不能撤销提供方的声明。</li>
 * </ol>
 * <b>为什么不按「工具 → 插件」归因</b>：工具名全局唯一这一点已由「{@code ToolCallRequest} + 同键唯一注册」
 * 保证，因此「哪个来源声明的只读」不影响判定结果，直接合并成扁平集合即可——也就不需要给扩展层
 * 新增 owner 归因能力。
 * <p>
 * 配置可疑（不是数组、含非字符串项）只发 {@link ConfigWarningEvent} 并跳过该项，不中断启动——
 * 与本仓库「配置好坏不阻断启动」的既有口径一致。
 * <p>
 * <b>为什么这一侧现算</b>：判定用的是「此刻注册表里有什么」，而工具清单侧改为按会话冻一份快照
 * （见 {@code ToolCatalog}）——两者并不矛盾：冻结是为了「同一个会话里发给模型的清单不变」，
 * 而权限判定关心的是「这个名字现在到底是不是只读」，没有历史包袱。
 * 配置侧若也现算，同一条非法配置会在<b>每次权限判定</b>时重复发告警，
 * 因此仍按快照引用缓存一份解析结果。{@code PluginRuntimeConfig} 保证同一快照期内返回同一个
 * configurations 实例，引用比较即可判定，正常路径下没有重复解析开销。
 *
 * @author zcd
 */
@Singleton
public final class ReadOnlyTools {

    /** 插件运行时装配输入，用户追加白名单的来源。 */
    private final PluginRuntimeConfig pluginConfig;

    /** 事件发布入口，用于广播配置告警。 */
    private final EventPublisher events;

    /** 同步扩展点策略，工具描述符的唯一来源。 */
    private final ExtensionRegistry extensions;

    /** 上次解析配置所依据的插件配置快照，用于判断是否需要重算。 */
    private Map<String, Map<String, Object>> parsedFrom;

    /** 由 {@link #parsedFrom} 派生的用户追加白名单。 */
    private volatile Set<String> declaredNames = Collections.emptySet();

    /**
     * 构造只读工具集合。
     * <p>
     * 构造期不解析配置：此时配置多半尚未刷新，读到的必然是空集合。
     *
     * @param pluginConfig 插件运行时装配输入，提供各插件配置段
     * @param events       事件发布入口，用于广播配置告警
     * @param extensions   同步扩展点策略，提供工具描述符
     */
    @Inject
    public ReadOnlyTools(PluginRuntimeConfig pluginConfig, EventPublisher events, ExtensionRegistry extensions) {
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "pluginConfig must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
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
     * 获取全部只读工具名（描述符声明 ∪ 用户追加），供权限判定与诊断使用。
     *
     * @return 不可修改集合，两处都没有声明时为空集合
     */
    public Set<String> names() {
        Set<String> collected = new LinkedHashSet<>(declared());
        for (ToolDescriptor descriptor : extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class)) {
            if (descriptor.isReadOnly()) {
                collected.add(descriptor.getName());
            }
        }
        return Collections.unmodifiableSet(collected);
    }

    /**
     * 获取用户追加的只读工具名，按插件配置快照缓存。
     *
     * @return 不可修改集合，未配置任何只读工具时为空集合
     */
    private Set<String> declared() {
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
