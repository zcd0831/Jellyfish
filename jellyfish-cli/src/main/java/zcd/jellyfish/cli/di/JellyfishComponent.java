package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.PermissionManager;

import javax.inject.Singleton;

/**
 * 应用级 Dagger2 组件：在最外层（composition root）装配共享的 {@code OkHttpClient}、
 * LLM 客户端注册表、配置门面、扩展层（注册表 / 同步策略 / 事件通道）、插件运行时与权限判定。
 *
 * @author zcd
 */
@Singleton
@Component(modules = {ConfigModule.class, LlmModule.class, ExtensionModule.class, EventModule.class,
        PluginModule.class, PermissionModule.class})
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
     * 获取权限管理器。
     * <p>
     * 当前尚无内核调用点（ReAct 循环未落地），此处先暴露装配结果，等调用点接入后由它同步询问。
     *
     * @return PermissionManager
     */
    PermissionManager permissionManager();
}
