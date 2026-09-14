package zcd.jellyfish.core;

import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Agent 运行时宿主与组装门面：外部入口（CLI / TUI / Server）只认它。
 * <p>
 * 目前只落地启动时序里与配置加载、索引建立相关的一环，其余职责为占位：
 * <ul>
 *     <li>已在实现：启动事件总线 → 加载运行时配置 → 重建模型 / agent 索引 → 刷新插件配置 → 启动插件运行时，
 *     保证启动期配置告警不丢失、各注册表在配置就绪后再建索引、插件在拿到最终的扫描目录与启用名单后启动；</li>
 *     <li>待实现：注册核心组件订阅者、驱动 ReAct 循环（思考 → 行动 → 观察）、关闭时优雅收敛。</li>
 * </ul>
 * 启动顺序有意固定为「先 {@code eventChannel.start()} → 再 {@code runtimeConfig.refresh()} →
 * 再各注册表 {@code refresh(...)} → 最后 {@code pluginManager.bootstrap()}」：
 * <ol>
 *     <li>通知订阅者注册完成后再加载配置，配置层发出的 {@code ConfigWarningEvent} 才能被订阅到；</li>
 *     <li>{@code ModelManager} / {@code AgentManager} 的索引必然建立在已加载的配置之上
 *     （两者构造期只建空索引，真正的装载就在这几行）；</li>
 *     <li>插件运行时在 {@code bootstrap()} 里才读扫描目录与启用 / 禁用名单，因此
 *     {@code pluginRuntimeConfig.refresh(...)} 必须排在它之前，否则插件按空配置启动。</li>
 * </ol>
 *
 * @author zcd
 */
@Singleton
public class AgentHarness {

    /** 运行时配置门面，负责配置的双源读取与合并。 */
    private final RuntimeConfig runtimeConfig;

    /** 事件通道：内核向外广播通知的异步通道。 */
    private final EventChannel eventChannel;

    /** 模型管理器，持有 provider / model 索引。 */
    private final ModelManager modelManager;

    /** agent 门面，持有 agent 定义与权限策略索引。 */
    private final AgentManager agentManager;

    /** 插件运行时装配输入：引用稳定、快照可换，由本类在插件启动前刷新。 */
    private final PluginRuntimeConfig pluginRuntimeConfig;

    /** 插件运行时门面：加载 / 体检 / 启动插件，并按 owner 回收注册。 */
    private final PF4JPluginManager pluginManager;

    /**
     * 构造运行时宿主。
     *
     * @param runtimeConfig        运行时配置门面
     * @param eventChannel         事件通道
     * @param modelManager         模型管理器
     * @param agentManager         agent 门面
     * @param pluginRuntimeConfig  插件运行时装配输入
     * @param pluginManager        插件运行时门面
     */
    @Inject
    public AgentHarness(RuntimeConfig runtimeConfig, EventChannel eventChannel, ModelManager modelManager,
                        AgentManager agentManager, PluginRuntimeConfig pluginRuntimeConfig,
                        PF4JPluginManager pluginManager) {
        this.runtimeConfig = runtimeConfig;
        this.eventChannel = eventChannel;
        this.modelManager = modelManager;
        this.agentManager = agentManager;
        this.pluginRuntimeConfig = pluginRuntimeConfig;
        this.pluginManager = pluginManager;
    }

    /**
     * 启动应用：启动事件通道 → 加载运行时配置 → 重建模型索引 → 重建 agent 索引 → 刷新插件配置 →
     * 启动插件运行时。
     * <p>
     * 其余启动步骤（注册核心订阅者）为占位，后续在此补充。
     */
    public void bootstrap() {
        eventChannel.start();
        runtimeConfig.refresh();
        modelManager.refresh(false);
        agentManager.refresh(false);
        // 必须在插件启动前：插件运行时此刻才读扫描目录与启用 / 禁用名单
        pluginRuntimeConfig.refresh(runtimeConfig.getPluginsSettings());
        pluginManager.bootstrap();
    }

    /**
     * 关闭应用：先停插件（并按 owner 回收注册），再收敛事件通道。幂等。
     * <p>
     * 顺序与 {@link #bootstrap()} 相反：插件先停，避免插件在通道关停后继续收到通知；
     * 两者都失败也不互相阻断，保证运行总能收敛。
     */
    public void shutdown() {
        try {
            pluginManager.close();
        } finally {
            eventChannel.close();
        }
    }
}
