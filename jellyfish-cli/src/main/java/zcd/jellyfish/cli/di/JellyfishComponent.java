package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Singleton;

/**
 * 应用级 Dagger2 组件：在最外层（composition root）装配共享的 {@code OkHttpClient}、
 * LLM 客户端注册表、配置门面、扩展层（注册表 / 同步策略 / 事件通道）、插件运行时、agent 定义与权限判定。
 *
 * @author zcd
 */
@Singleton
@Component(modules = {ConfigModule.class, LlmModule.class, ExtensionModule.class, EventModule.class,
        PluginModule.class, AgentModule.class, PermissionModule.class, CommandModule.class, MetricsModule.class})
public interface JellyfishComponent {

    /**
     * 获取 LLM 客户端工厂。
     *
     * @return LlmClientFactory
     */
    LlmClientFactory llmClientFactory();

    /**
     * 获取模型管理器。
     *
     * @return ModelManager
     */
    ModelManager modelManager();

    /**
     * 获取运行时配置门面。
     *
     * @return RuntimeConfig
     */
    RuntimeConfig runtimeConfig();

    /**
     * 获取 Agent 运行时宿主，由外壳调用 {@code bootstrap()} 启动。
     *
     * @return AgentHarness
     */
    AgentHarness agentHarness();

    /**
     * 获取 agent 门面。
     *
     * @return AgentManager
     */
    AgentManager agentManager();

    /**
     * 获取权限管理器。
     * <p>
     * 调用点是 {@code ReActLooper}：每次工具执行前同步询问，判定结果决定是否回灌拒绝理由。
     *
     * @return PermissionManager
     */
    PermissionManager permissionManager();

    /**
     * 获取人工审批通道。
     * <p>
     * 调用点是外壳装配：只有 {@code -tui} 会 {@code attach()}——它需要独占终端的模态交互；
     * {@code -cli} 与 {@code -server} 不挂审批者，因此「需要审批」的工具一律按拒绝处理。
     *
     * @return ApprovalChannel
     */
    ApprovalChannel approvalChannel();

    /**
     * 获取会话压缩器。
     * <p>
     * 调用点是外壳装配：{@code TuiRunMode} 把它交给界面用于展示压缩状态；
     * 命令侧（{@code /compact}）由内核系统命令直接注入，不经外壳。
     *
     * @return ConversationCompactor
     */
    ConversationCompactor conversationCompactor();

    /**
     * 获取输入指令服务。
     * <p>
     * 调用点是外壳：{@code -tui} 用它把 {@code !} 这类行首指令变成工具调用、把 {@code @} 这类行内标记
     * 变成补全候选；内核侧只有 {@code AgentHarness} 持有一份用于关闭。
     *
     * @return InputDirectives
     */
    InputDirectives inputDirectives();

    /**
     * 获取命令域服务。
     * <p>
     * 调用点分两类：外壳不再直接调用（分流已收归 {@code ConversationService}），
     * 只有 Server 的 {@code POST /commands} 结构化入口与 {@code GET /commands*} 清单直调；
     * TUI 只用它取清单做补全与候选查询。
     * <p>
     * 内核系统命令已由 {@code core/command/SystemCommands} 在 {@code AgentHarness.bootstrap()} 里注册。
     *
     * @return CommandManager
     */
    CommandManager commandManager();

    /**
     * 获取会话域服务。
     * <p>
     * 调用点是外壳：启动期由 {@code SessionBootstrap} 保证「有当前会话」，运行期由各模式每轮现读
     * {@code current()} 拿会话标识（命令会改写当前会话，因此外壳不缓存 sessionId）。
     *
     * @return SessionManager
     */
    SessionManager sessionManager();

    /**
     * 取本进程内新建会话的待生效默认值。
     *
     * @return SessionDefaults
     */
    SessionDefaults sessionDefaults();

    /**
     * 获取同步扩展点策略。
     * <p>
     * 调用点是外壳装配：{@code TuiRunMode} 用它构造 {@code UiContributions}——
     * 「向插件收集界面内容」是外壳对扩展层的唯一需求，外壳本身不直接读注册表。
     *
     * @return ExtensionRegistry
     */
    ExtensionRegistry extensionRegistry();

    /**
     * 获取事件通道。
     * <p>
     * 调用点是外壳装配：{@code TuiRunMode} 用它构造 {@code UiContributions}，订阅插件发布的
     * {@code UiInvalidatedEvent} 与内核的 {@code PluginStateChangedEvent}。
     *
     * @return EventChannel
     */
    EventChannel eventChannel();

    /**
     * 获取运行时信息持有者。
     * <p>
     * 调用点是 {@code Launcher}：外壳种类只有在选完运行模式之后才知道，因此由装配根在
     * {@code bootstrap()} 之前写入，插件在 {@code start()} 里就能读到最终值。
     *
     * @return RuntimeInfoHolder
     */
    RuntimeInfoHolder runtimeInfoHolder();

    /**
     * 获取会话提交服务。
     * <p>
     * 调用点是三个外壳的输入入口：分流顺序（命令判定 → 输入改写 → 输入指令 → 起回合）由它一处保证，
     * 外壳只声明自己的 {@code SubmissionPolicy} 并处理判别式结果。
     *
     * @return ConversationService
     */
    ConversationService conversationService();

    /**
     * 获取在途回合表。
     * <p>
     * 调用点是 Server 外壳的取消端点（{@code POST /sessions/{id}/cancel}）；
     * TUI 的 {@code Esc} 也用它。起回合时的占位与终态释放由内核的 {@code ConversationService}
     * 自动完成，调用方不需要（也不应该）自己 acquire / release。
     *
     * @return TurnRegistry
     */
    TurnRegistry turnRegistry();

    /**
     * 获取外壳通道门面（两条 lane 的订阅入口）。
     * <p>
     * <b>可靠 lane</b>（回合事件）：三个外壳都在提交之前先订阅——Server 按会话订阅
     * （避免把别的会话的事件写进自己的响应），TUI 用 {@code subscribeAll}
     * （它从首页进入，提交之前拿不到会话标识），CLI 按当前会话订阅。
     * <p>
     * <b>尽力 lane</b>（插件贡献）：TUI 在 {@code onStart} 订阅一次并在每帧 {@code drainShell()}；
     * Server 每次 {@code /chat} 订阅一次，在 SSE 写循环里冲。CLI 不订阅
     * （它没有渲染面，{@code present} 会直接被内核挡下并回报 {@code DROPPED_NO_RENDERER}）。
     *
     * @return ShellStreams
     */
    ShellStreams shellStreams();

    /**
     * 获取健康检查汇总。
     * <p>
     * 调用点是外壳装配：{@code -server} 用它实现 {@code GET /health}——服务化之后
     * 「这个进程还健康吗」需要一个不依赖模型的查询面。CLI / TUI 不使用（它们各自用日志汇报）。
     *
     * @return HealthCheck
     */
    HealthCheck healthCheck();
}
