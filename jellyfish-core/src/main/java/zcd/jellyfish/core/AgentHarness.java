package zcd.jellyfish.core;

import zcd.jellyfish.core.command.SystemCommands;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Agent 运行时宿主与组装门面：外部入口（CLI / TUI / Server）只认它。
 * <p>
 * 目前落地了启动时序里与配置加载、索引建立、核心命令注册相关的一环：
 * <ul>
 *     <li>已在实现：启动事件总线 → 注册核心系统命令 → 加载运行时配置 → 重建模型 / agent 索引 →
 *     刷新插件配置 → 启动插件运行时 → 向插件恢复历史会话，保证启动期配置告警不丢失、各注册表在配置就绪后
 *     再建索引、插件在拿到最终的扫描目录与启用名单后启动、会话在插件注册好处理器之后才被问；核心命令
 *     先于插件注册，插件要覆盖同名命令必须显式声明 {@code override}；</li>
 *     <li>已在实现：驱动 ReAct 循环（{@link #chat} 委托 {@link ReActLooper}）；关闭时优雅收敛。</li>
 * </ul>
 * 启动顺序有意固定为「先 {@code eventChannel.start()} → 再注册核心命令 → 再 {@code runtimeConfig.refresh()} →
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

    /** ReAct 循环器：本门面唯一智能入口的执行体。 */
    private final ReActLooper reActLooper;

    /** 内核系统命令注册器：{@code /help} 等。 */
    private final SystemCommands systemCommands;

    /** 会话域服务：启动末期向插件要回历史会话。 */
    private final SessionManager sessionManager;

    /**
     * 构造运行时宿主。
     *
     * @param runtimeConfig        运行时配置门面
     * @param eventChannel         事件通道
     * @param modelManager         模型管理器
     * @param agentManager         agent 门面
     * @param pluginRuntimeConfig  插件运行时装配输入
     * @param pluginManager        插件运行时门面
     * @param reActLooper          ReAct 循环器
     * @param systemCommands       内核系统命令注册器
     * @param sessionManager       会话域服务
     */
    @Inject
    public AgentHarness(RuntimeConfig runtimeConfig, EventChannel eventChannel, ModelManager modelManager,
                        AgentManager agentManager, PluginRuntimeConfig pluginRuntimeConfig,
                        PF4JPluginManager pluginManager, ReActLooper reActLooper, SystemCommands systemCommands,
                        SessionManager sessionManager) {
        this.runtimeConfig = runtimeConfig;
        this.eventChannel = eventChannel;
        this.modelManager = modelManager;
        this.agentManager = agentManager;
        this.pluginRuntimeConfig = pluginRuntimeConfig;
        this.pluginManager = pluginManager;
        this.reActLooper = reActLooper;
        this.systemCommands = systemCommands;
        this.sessionManager = sessionManager;
    }

    /**
     * 启动应用：启动事件通道 → 注册核心命令 → 加载运行时配置 → 重建模型索引 → 重建 agent 索引 →
     * 刷新插件配置 → 启动插件运行时 → 向插件要回历史会话。
     * <p>
     * 核心命令先于插件注册：插件若要覆盖同名系统命令，必须显式声明 {@code override}，
     * 否则会在插件启动时以 {@code DUPLICATE_HANDLER} 当场暴露，而不是静默地两套并存。
     * <p>
     * 会话恢复必须在 {@code pluginManager.bootstrap()} <b>之后</b>：插件要先注册恢复处理器，
     * 才可能被问到。
     */
    public void bootstrap() {
        eventChannel.start();
        systemCommands.register();
        runtimeConfig.refresh();
        modelManager.refresh(false);
        agentManager.refresh(false);
        // 必须在插件启动前：插件运行时此刻才读扫描目录与启用 / 禁用名单
        pluginRuntimeConfig.refresh(runtimeConfig.getPluginRoots(), runtimeConfig.getPluginsSettings());
        pluginManager.bootstrap();
        // 必须在插件启动后：插件此刻才注册好恢复处理器
        sessionManager.restore();
    }

    /**
     * 启动一次 ReAct 回合：本门面唯一的智能入口。
     *
     * @param sessionId 会话标识，不可为空白
     * @param userInput 用户输入，可为 {@code null}
     * @param listener  流式回调，可为 {@code null}（等价于 {@link ReActListener#NOOP}）
     * @return 回合句柄，保证非 {@code null}
     */
    public ReActTurn chat(String sessionId, String userInput, ReActListener listener) {
        return reActLooper.chat(sessionId, userInput, listener);
    }

    /**
     * 关闭应用：先停 ReAct 循环（不再接新回合），再回收核心命令，再停插件（并按 owner 回收注册），
     * 最后收敛事件通道。幂等。
     * <p>
     * 顺序与 {@link #bootstrap()} 相反；任何一步失败都不阻断后续步骤，保证运行总能收敛。
     */
    public void shutdown() {
        try {
            reActLooper.close();
        } finally {
            try {
                systemCommands.close();
            } finally {
                try {
                    pluginManager.close();
                } finally {
                    eventChannel.close();
                }
            }
        }
    }
}
