package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.PermissionManager;

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
     * 当前尚无外壳调用点（CLI / TUI / Server / Web 都未落地），此处先暴露装配结果：
     * 任一外壳都可以用它解析并执行命令，或取结构化清单自行渲染菜单。
     * 内核系统命令已由 {@code core/command/SystemCommands} 在 {@code AgentHarness.bootstrap()} 里注册。
     *
     * @return CommandManager
     */
    CommandManager commandManager();
}
