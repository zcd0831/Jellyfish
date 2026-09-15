package zcd.jellyfish.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.core.command.SystemCommands;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.PluginsSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.session.SessionManager;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

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

    /** ReAct 循环器。 */
    @Mock
    private ReActLooper reActLooper;

    /** 会话域服务：启动末期由它向插件要回历史会话。 */
    @Mock
    private SessionManager sessionManager;

    /** 内核系统命令注册器。 */
    @Mock
    private SystemCommands systemCommands;

    @Test
    void bootstrap_should_start_in_fixed_order() {
        // Given
        PluginsSettings plugins = new PluginsSettings(null, null, null);
        List<Path> roots = Collections.emptyList();
        when(runtimeConfig.getPluginsSettings()).thenReturn(plugins);
        when(runtimeConfig.getPluginRoots()).thenReturn(roots);
        AgentHarness harness = newHarness();

        // When
        harness.bootstrap();

        // Then：事件订阅者就绪 → 注册核心命令 → 配置 → 各索引 → 插件配置 → 插件启动 → 会话恢复
        InOrder order = inOrder(eventChannel, systemCommands, runtimeConfig, modelManager, agentManager,
                pluginRuntimeConfig, pluginManager, sessionManager);
        order.verify(eventChannel).start();
        order.verify(systemCommands).register();
        order.verify(runtimeConfig).refresh();
        order.verify(modelManager).refresh(false);
        order.verify(agentManager).refresh(false);
        order.verify(pluginRuntimeConfig).refresh(roots, plugins);
        order.verify(pluginManager).bootstrap();
        // 恢复必须排在插件启动之后：插件此刻才注册好恢复处理器
        order.verify(sessionManager).restore();
    }

    @Test
    void bootstrap_should_refresh_plugin_config_with_latest_snapshot() {
        // Given
        PluginsSettings plugins = new PluginsSettings(null, null, null);
        List<Path> roots = Collections.emptyList();
        when(runtimeConfig.getPluginsSettings()).thenReturn(plugins);
        when(runtimeConfig.getPluginRoots()).thenReturn(roots);

        // When
        newHarness().bootstrap();

        // Then：扫描目录与名单必须来自同一轮配置读取
        verify(pluginRuntimeConfig).refresh(roots, plugins);
    }

    @Test
    void shutdown_should_converge_in_reverse_order() {
        // Given
        AgentHarness harness = newHarness();

        // When
        harness.shutdown();

        // Then：先停 ReAct，再回收核心命令，再插件，最后通道
        InOrder order = Mockito.inOrder(reActLooper, systemCommands, pluginManager, eventChannel);
        order.verify(reActLooper).close();
        order.verify(systemCommands).close();
        order.verify(pluginManager).close();
        order.verify(eventChannel).close();
    }

    @Test
    void chat_should_delegate_to_looper() {
        // Given
        AgentHarness harness = newHarness();
        ReActListener listener = new ReActListener() {
        };
        ReActTurn turn = Mockito.mock(ReActTurn.class);
        when(reActLooper.chat("s1", "hi", listener)).thenReturn(turn);

        // When
        ReActTurn result = harness.chat("s1", "hi", listener);

        // Then
        Mockito.verify(reActLooper).chat("s1", "hi", listener);
        org.junit.jupiter.api.Assertions.assertSame(turn, result);
    }

    /**
     * 构造被测实例。
     *
     * @return AgentHarness 实例
     */
    private AgentHarness newHarness() {
        return new AgentHarness(runtimeConfig, eventChannel, modelManager, agentManager, pluginRuntimeConfig,
                pluginManager, reActLooper, systemCommands, sessionManager);
    }
}
