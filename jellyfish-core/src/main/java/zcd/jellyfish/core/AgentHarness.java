package zcd.jellyfish.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zcd.jellyfish.core.command.SystemCommands;
import zcd.jellyfish.core.runtime.RunObservationBridge;
import zcd.jellyfish.core.runtime.RunScheduler;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.prompt.CacheKeepAlive;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.subagent.SubAgentTools;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ConfigWarningReporter;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.metrics.MetricsSubscriber;
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
 *     刷新插件配置 → 启动插件运行时 → 问一次插件模型目录 → 向插件恢复历史会话，保证启动期配置告警不丢失、
 *     各注册表在配置就绪后再建索引、插件在拿到最终的扫描目录与启用名单后启动、目录发现与
 *     会话恢复都在插件注册好处理器之后才发生；核心命令先于插件注册，插件要覆盖同名命令必须显式声明
 *     {@code override}；</li>
 *     <li>已在实现：驱动 ReAct 循环（{@link #chat} 委托 {@link ReActLooper}）；关闭时优雅收敛。</li>
 * </ul>
 * 启动顺序有意固定为「先 {@code eventChannel.start()} → 再注册核心命令 → 再 {@code runtimeConfig.refresh()} →
 * 再各注册表 {@code refresh(...)} → 再 {@code pluginManager.bootstrap()} → 再
 * {@code modelManager.refreshCatalogs()} → 最后 {@code sessionManager.restore()}」：
 * <ol>
 *     <li>通知订阅者注册完成后再加载配置，配置层发出的 {@code ConfigWarningEvent} 才能被订阅到；</li>
 *     <li>{@code ModelManager} / {@code AgentManager} 的索引必然建立在已加载的配置之上
 *     （两者构造期只建空索引，真正的装载就在这几行）；</li>
 *     <li>插件运行时在 {@code bootstrap()} 里才读扫描目录与启用 / 禁用名单，因此
 *     {@code pluginRuntimeConfig.refresh(...)} 必须排在它之前，否则插件按空配置启动；</li>
 *     <li>模型目录发现必须排在 {@code pluginManager.bootstrap()} <b>之后</b>：传输实现与目录处理器
 *     都是插件在那一步才注册的，早于它问只会得到空目录。</li>
 * </ol>
 *
 * @author zcd
 */
@Singleton
public class AgentHarness {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentHarness.class);

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

    /** 子代理能力注册器：{@code task} 工具与可委派类型清单。 */
    private final SubAgentTools subAgentTools;

    /** 会话压缩器：{@code /compact} 的执行体，关闭时要先停掉它的线程池。 */
    private final ConversationCompactor conversationCompactor;

    /**
     * 缓存保活器：空闲时续厂商侧缓存 TTL（缺省关闭）。
     * <p>
     * 它不需要任何人调用——构造期就把扫描排进了自己的守护线程。本类持有它<b>只是为了两件事</b>：
     * 一是在 Dagger 图里可达（没人注入的 {@code @Singleton} 根本不会被创建），
     * 二是关闭时能把那个线程收干净。
     */
    private final CacheKeepAlive cacheKeepAlive;

    /** 输入指令服务：关闭时要先停掉它的线程池并取消在途命令。 */
    /** 输入指令服务：`!` 那条路。 */
    private final InputDirectives inputDirectives;

    /** run 调度器：子代理 run 的取消与收尾（关停时取消在途 run）。 */
    private final RunScheduler runScheduler;

    /** 会话域服务：启动末期向插件要回历史会话。 */
    private final SessionManager sessionManager;

    /** 指标订阅者：可观测性这条边上的唯一写入方。 */
    private final MetricsSubscriber metricsSubscriber;

    /**
     * 配置告警上报器：把「配置有问题」这类事件打成一行 WARN 日志。
     * <p>
     * 它的可观测面与 {@link #metricsSubscriber} 互补：那个只计数（{@code config.warnings}），
     * 只有数量没有内容，而用户需要知道的是「哪个文件的哪个字段错了」。
     */
    private final ConfigWarningReporter configWarningReporter;

    /** run 观测桥：把 run 的开始与结束翻成插件看得懂的通知事件。 */
    private final RunObservationBridge runObservation;

    /** 指标注册表：关闭时打一份汇总日志。 */
    private final MetricsRegistry metricsRegistry;

    /** 健康检查：关闭时打一份诊断快照。 */
    private final HealthCheck healthCheck;

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
     * @param subAgentTools        子代理能力注册器
     * @param sessionManager       会话域服务
     * @param conversationCompactor 会话压缩器
     * @param inputDirectives      输入指令服务
     * @param runScheduler         子代理 run 调度器（关闭时要取消在途 run）
     * @param cacheKeepAlive       缓存保活器
     * @param metricsSubscriber    指标订阅者
     * @param configWarningReporter 配置告警上报器
     * @param metricsRegistry      指标注册表
     * @param healthCheck          健康检查
     */
    @Inject
    public AgentHarness(RuntimeConfig runtimeConfig, EventChannel eventChannel, ModelManager modelManager,
                        AgentManager agentManager, PluginRuntimeConfig pluginRuntimeConfig,
                        PF4JPluginManager pluginManager, ReActLooper reActLooper, SystemCommands systemCommands,
                        SubAgentTools subAgentTools, SessionManager sessionManager,
                        ConversationCompactor conversationCompactor, InputDirectives inputDirectives,
                        RunScheduler runScheduler, CacheKeepAlive cacheKeepAlive,
                        MetricsSubscriber metricsSubscriber, ConfigWarningReporter configWarningReporter,
                        MetricsRegistry metricsRegistry,
                        RunObservationBridge runObservation, HealthCheck healthCheck) {
        this.runtimeConfig = runtimeConfig;
        this.eventChannel = eventChannel;
        this.modelManager = modelManager;
        this.agentManager = agentManager;
        this.pluginRuntimeConfig = pluginRuntimeConfig;
        this.pluginManager = pluginManager;
        this.reActLooper = reActLooper;
        this.systemCommands = systemCommands;
        this.subAgentTools = subAgentTools;
        this.sessionManager = sessionManager;
        this.conversationCompactor = conversationCompactor;
        this.inputDirectives = inputDirectives;
        this.runScheduler = runScheduler;
        this.cacheKeepAlive = cacheKeepAlive;
        this.metricsSubscriber = metricsSubscriber;
        this.configWarningReporter = configWarningReporter;
        this.runObservation = runObservation;
        this.metricsRegistry = metricsRegistry;
        this.healthCheck = healthCheck;
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
        // 必须在 runtimeConfig.refresh() 之前：配置加载期发出的告警要能被计数
        metricsSubscriber.start();
        // 同上，而且更直接：配置加载期的告警（绝大多数都是那时发的）要能被人看见
        configWarningReporter.start();
        // 与指标同理：订阅要先于任何 run 建立，否则最早的几次委派在插件侧是隐形的
        runObservation.start();
        systemCommands.register();
        // 与系统命令同理：内核先注册，插件要覆盖 {@code task} 必须显式声明 override
        subAgentTools.register();
        runtimeConfig.refresh();
        modelManager.refresh(false);
        agentManager.refresh(false);
        // 必须在插件启动前：插件运行时此刻才读扫描目录与启用 / 禁用名单
        pluginRuntimeConfig.refresh(runtimeConfig.getPluginRoots(), runtimeConfig.getPluginsSettings());
        pluginManager.bootstrap();
        // 必须在插件启动后：传输实现与目录处理器都是插件在那一步才注册的，先问只会得到空目录
        modelManager.refreshCatalogs();
        // 必须在插件启动后：插件此刻才注册好恢复处理器
        sessionManager.restore();
    }

    /**
     * 启动一次 ReAct 回合：本门面唯一的智能入口。
     * <p>
     * <b>它是内核内部接缝，不是外壳接口</b>：外壳只经
     * {@code ConversationService.submit} + {@code ShellStreams.subscribe} 两条边工作，
     * 不再自己传监听器。保留本方法是为了让内核能把「回合执行体」与「事件发布」分开装配
     * （发布器由 {@code ConversationService} 提供），子代理委派也仍然需要一条同步回调。
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
     * 启动一次 ReAct 回合，使用外部给定的回合标识（内核内部接缝）。
     *
     * @param sessionId 会话标识，不可为空白
     * @param turnId    回合标识，不可为空白
     * @param userInput 用户输入，可为 {@code null}
     * @param listener  流式回调，可为 {@code null}
     * @return 回合句柄，保证非 {@code null}
     */
    public ReActTurn chat(String sessionId, String turnId, String userInput, ReActListener listener) {
        return reActLooper.chat(sessionId, turnId, userInput, listener);
    }

    /**
     * 关闭应用：先打一份健康检查（此刻各组件还在运行，报告才有诊断价值），
     * 再停 ReAct 循环与压缩器（不再接新回合、不再起新压缩），把未落盘的会话补上，
     * 再回收核心命令，再停插件（并按 owner 回收注册），最后收敛事件通道；收尾时退订指标并打一份运行期指标汇总。幂等。
     * <p>
     * <b>{@code flushAll} 的位置是硬约束</b>：它必须在插件停止<b>之前</b>——落盘经
     * {@code ExtensionRegistry} 派发 {@code SessionPersistRequest}，插件一旦停掉就没人接了。
     * 有了它，「回合级落盘」才不至于把「正常退出丢当前回合」变成新行为。
     * <p>
     * 顺序与 {@link #bootstrap()} 相反；任何一步失败都不阻断后续步骤，保证运行总能收敛。
     */
    public void shutdown() {
        // 先报健康：此刻插件与通道还在运行，报告才有诊断价值（关闭后每项都会是 DOWN）
        LOG.info("健康检查: {}", healthReportText());
        try {
            reActLooper.close();
            // 在途 run 与在途回合同类：先取消并让它们走到终态，再谈落盘与摘插件。
            // run 会写子会话、会调插件工具，因此这一步必须早于 flushAll 与 pluginManager.close()；
            // 而它在 reActLooper.close() 之后，是因为「不再起新回合」先于「收在途的 run」
            runScheduler.close();
            // 与 reActLooper 同类：先停「还会发起工具调用」的入口，再谈落盘与摘插件。
            // 它内部会取消在途命令，否则关闭会被一条长命令拖到它自己的超时
            inputDirectives.close();
            conversationCompactor.close();
            // 保活扫描要组装请求、要连厂商，因此和上面两个同类：先停掉它，再谈落盘与摘插件。
            // 它本是守护线程，不关也不会拖住退出；但显式关掉能让「关闭之后不再有新请求」成为事实
            cacheKeepAlive.close();
            // 必须先于 pluginManager.close()：落盘要经扩展点派发给插件
            sessionManager.flushAll();
        } finally {
            try {
                systemCommands.close();
            } finally {
                try {
                    // 内核自己的注册与命令同类：必须在插件停止之前回收，否则会留下无人认领的注册
                    subAgentTools.close();
                } finally {
                    try {
                        pluginManager.close();
                    } finally {
                        eventChannel.close();
                    }
                }
            }
        }
        try {
            // 指标是累计值，放在关闭之后不影响可读性；先退订再读快照，避免读到一半又变
            metricsSubscriber.close();
            // 与指标同批：它也只是个订阅者，关掉后不再有配置告警的日志行
            configWarningReporter.close();
            runObservation.close();
        } finally {
            LOG.info("运行期指标: {}", metricsSnapshotText());
        }
    }

    /**
     * 渲染健康报告，检查失败时退化为一句说明。
     *
     * @return 报告文本
     */
    private String healthReportText() {
        try {
            return healthCheck.check().render();
        } catch (RuntimeException e) {
            // 关闭路径上的诊断日志绝不能把关闭本身弄失败
            LOG.warn("健康检查失败", e);
            return "（检查失败）";
        }
    }

    /**
     * 渲染运行期指标，取快照失败时退化为一句说明。
     *
     * @return 指标文本
     */
    private String metricsSnapshotText() {
        try {
            return metricsRegistry.snapshot().render();
        } catch (RuntimeException e) {
            // 关闭路径上的诊断日志绝不能把关闭本身弄失败
            LOG.warn("指标快照生成失败", e);
            return "（快照生成失败）";
        }
    }
}
