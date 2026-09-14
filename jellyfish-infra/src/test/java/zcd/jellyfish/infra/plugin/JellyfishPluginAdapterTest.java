package zcd.jellyfish.infra.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.pf4j.RuntimeMode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JellyfishPluginAdapter} 的单元测试：验证只做委派，不吞异常、不改状态。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class JellyfishPluginAdapterTest {

    /** 插件实现。 */
    @Mock
    private JellyfishPlugin delegate;

    /** 能力上下文。 */
    @Mock
    private PluginContext context;

    /** 被测适配器。 */
    private JellyfishPluginAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JellyfishPluginAdapter(wrapper(), delegate, context);
    }

    @Test
    void start_should_delegate_with_same_context() {
        // When
        adapter.start();

        // Then
        verify(delegate).start(context);
    }

    @Test
    void stop_should_delegate() {
        // When
        adapter.stop();

        // Then
        verify(delegate).stop();
    }

    @Test
    void start_should_propagate_exception() {
        // Given
        doThrow(new JellyfishException("boom")).when(delegate).start(context);

        // When / Then：适配器不吞异常，状态与失败原因由管理器的安全包装统一补齐
        assertThrows(JellyfishException.class, () -> adapter.start());
    }

    /**
     * 构造插件包装器。
     * <p>
     * {@code PluginWrapper} 构造器会读取 {@code PluginManager.getRuntimeMode()}，因此不能用空实现。
     *
     * @return 插件包装器
     */
    private static PluginWrapper wrapper() {
        PluginManager pluginManager = mock(PluginManager.class);
        when(pluginManager.getRuntimeMode()).thenReturn(RuntimeMode.DEPLOYMENT);
        return new PluginWrapper(pluginManager, new JellyfishPluginDescriptor(), Paths.get("plugins"), null);
    }
}
