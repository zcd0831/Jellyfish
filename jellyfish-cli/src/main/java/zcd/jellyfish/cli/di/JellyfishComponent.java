package zcd.jellyfish.cli.di;

import dagger.Component;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.model.ModelManager;

import javax.inject.Singleton;

/**
 * 应用级 Dagger2 组件：在最外层（composition root）装配共享的 {@code OkHttpClient}、
 * LLM 客户端注册表、配置门面与上层 manager。
 *
 * @author zcd
 */
@Singleton
@Component(modules = {ConfigModule.class, LlmModule.class, EventModule.class})
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
}
