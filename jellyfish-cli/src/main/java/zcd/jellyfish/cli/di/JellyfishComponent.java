package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
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
     * 调用点是外壳：{@code CliRunMode} 用它做「命令还是对话」的分流并执行命令，
     * 将来的 TUI / Server 走同一条路径。
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
     * 获取健康检查汇总。
     * <p>
     * 调用点是外壳装配：{@code -server} 用它实现 {@code GET /health}——服务化之后
     * 「这个进程还健康吗」需要一个不依赖模型的查询面。CLI / TUI 不使用（它们各自用日志汇报）。
     *
     * @return HealthCheck
     */
    HealthCheck healthCheck();
}
