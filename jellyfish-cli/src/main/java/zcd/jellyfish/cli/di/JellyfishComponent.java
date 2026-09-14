package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.PermissionManager;
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
        PluginModule.class, AgentModule.class, PermissionModule.class, CommandModule.class})
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
}
