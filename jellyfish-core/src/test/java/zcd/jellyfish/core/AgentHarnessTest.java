package zcd.jellyfish.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.PluginsSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentHarness} 的单元测试：用 {@code InOrder} 钉住启动顺序。
 * <p>
 * 这是本轮唯一的跨模块时序契约：所有配置驱动的注册表都在 {@code bootstrap()} 里建索引，
 * 顺序错了不会报错、只会静默空转（例如插件按空配置启动、agent 索引永远为空），
 * 因此必须用断言把它固定下来。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class AgentHarnessTest {

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 事件通道。 */
    @Mock
    private EventChannel eventChannel;

    /** 模型管理器。 */
    @Mock
    private ModelManager modelManager;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 插件运行时装配输入。 */
    @Mock
    private PluginRuntimeConfig pluginRuntimeConfig;

    /** 插件运行时门面。 */
    @Mock
    private PF4JPluginManager pluginManager;

    @Test
    void bootstrap_should_start_in_fixed_order() {
        // Given
        PluginsSettings plugins = new PluginsSettings(null, null, null, null);
        when(runtimeConfig.getPluginsSettings()).thenReturn(plugins);
        AgentHarness harness = newHarness();

        // When
        harness.bootstrap();

        // Then：事件订阅者就绪 → 配置 → 各索引 → 插件配置 → 插件启动
        InOrder order = inOrder(eventChannel, runtimeConfig, modelManager, agentManager, pluginRuntimeConfig,
                pluginManager);
        order.verify(eventChannel).start();
        order.verify(runtimeConfig).refresh();
        order.verify(modelManager).refresh(false);
        order.verify(agentManager).refresh(false);
        order.verify(pluginRuntimeConfig).refresh(plugins);
        order.verify(pluginManager).bootstrap();
    }

    @Test
    void bootstrap_should_refresh_plugin_config_with_latest_snapshot() {
        // Given
        PluginsSettings plugins = new PluginsSettings(null, null, null, null);
        when(runtimeConfig.getPluginsSettings()).thenReturn(plugins);

        // When
        newHarness().bootstrap();

        // Then
        verify(pluginRuntimeConfig).refresh(plugins);
    }

    @Test
    void shutdown_should_close_plugin_manager_before_event_channel() {
        // Given
        AgentHarness harness = newHarness();

        // When
        harness.shutdown();

        // Then：插件先停，避免它在通道关停后继续收到通知
        InOrder order = Mockito.inOrder(pluginManager, eventChannel);
        order.verify(pluginManager).close();
        order.verify(eventChannel).close();
    }

    /**
     * 构造被测实例。
     *
     * @return AgentHarness 实例
     */
    private AgentHarness newHarness() {
        return new AgentHarness(runtimeConfig, eventChannel, modelManager, agentManager, pluginRuntimeConfig,
                pluginManager);
    }
}
