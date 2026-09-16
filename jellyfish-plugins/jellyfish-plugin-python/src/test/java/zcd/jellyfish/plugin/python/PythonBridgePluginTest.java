package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Python 桥接插件生命周期的单元测试。
 * <p>
 * 钉住的核心性质是<b>能力上下文每次 start 现造、stop 现释放</b>：PF4J 会长期缓存插件实例
 * （{@code stop} 不丢弃它），因此语言适配与将来的网关必须在 {@code start()} 重建，
 * 否则「停止再启动」这个唯一的插件重启语义会继续拿着旧配置。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Python 桥接插件生命周期")
class PythonBridgePluginTest {

    /** 插件标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-python";

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("start 应按配置建立语言适配")
    void start_should_buildLanguage_when_configurationIsGiven() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(PythonConfig.KEY_PYTHON_PATH, "/opt/venv/bin/python3");
        when(context.configuration()).thenReturn(configuration);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        PythonBridgePlugin plugin = new PythonBridgePlugin();

        plugin.start(context);

        assertNotNull(plugin.language());
        assertEquals("/opt/venv/bin/python3",
                plugin.language().probeCommand().get(0));
    }

    @Test
    @DisplayName("start 不应要求脚本目录存在，环境缺失不能阻塞内核启动")
    void start_should_notRequireScriptsRoot_when_directoryDoesNotExist() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        PythonBridgePlugin plugin = new PythonBridgePlugin();

        plugin.start(context);

        assertNotNull(plugin.language());
    }

    @Test
    @DisplayName("start 遇到非法配置应上抛，交由框架转成插件 FAILED")
    void start_should_propagateJellyfishException_when_configurationIsInvalid() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(PythonConfig.KEY_SCRIPTS_ROOT, 7);
        when(context.configuration()).thenReturn(configuration);
        PythonBridgePlugin plugin = new PythonBridgePlugin();

        assertThrows(JellyfishException.class, () -> plugin.start(context));
    }

    @Test
    @DisplayName("stop 应释放语言适配，使下一次 start 重建")
    void stop_should_releaseLanguage_when_calledAfterStart() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        PythonBridgePlugin plugin = new PythonBridgePlugin();
        plugin.start(context);

        plugin.stop();

        assertNull(plugin.language());
    }

    @Test
    @DisplayName("stop 应释放台账，使下一次 start 重新扫描")
    void stop_should_releaseLedger_when_calledAfterStart() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        PythonBridgePlugin plugin = new PythonBridgePlugin();
        plugin.start(context);

        plugin.stop();

        assertEquals(0, plugin.ledger().scriptCount());
        assertTrue(plugin.ledger().issues().isEmpty());
    }

    @Test
    @DisplayName("未启动就 stop 应是安全空操作")
    void stop_should_beNoOp_when_pluginWasNeverStarted() {
        PythonBridgePlugin plugin = new PythonBridgePlugin();

        plugin.stop();

        assertNull(plugin.language());
    }

    @Test
    @DisplayName("start 应幂等地重建适配，重复调用不报错")
    void start_should_rebuildLanguage_when_calledTwice() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        PythonBridgePlugin plugin = new PythonBridgePlugin();

        plugin.start(context);
        PythonLanguage first = plugin.language();
        plugin.start(context);

        assertNotNull(plugin.language());
        assertNotNull(first);
    }
}
