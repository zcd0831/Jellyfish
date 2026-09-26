package zcd.jellyfish.plugin.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Node 桥接插件的单元测试。
 * <p>
 * 生命周期本身（start 现造、stop 现释放、运行时经熔断、注册按命名空间回收）由
 * {@code jellyfish-script} 的 {@code ScriptBridgePluginTest} 用假语言覆盖，这里<b>只钉 Node 那一部分</b>：
 * 解释器写在哪个键上、默认值是什么、脚本根目录默认落在哪。
 * <p>
 * 这几条看着琐碎，但它们正是「上收」之后唯一可能悄悄出错的地方——公共实现不再知道
 * {@code nodePath} 这个词，因此必须有人替它记住。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Node 桥接插件")
class NodeBridgePluginTest {

    /** 插件标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-node";

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("解释器应取 nodePath 键")
    void start_should_buildLanguage_when_nodePathIsConfigured() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(NodeBridgePlugin.KEY_INTERPRETER, "/opt/node/bin/node");
        when(context.configuration()).thenReturn(configuration);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        plugin.start(context);

        assertNotNull(plugin.language());
        assertEquals("/opt/node/bin/node", plugin.language().probeCommand().get(0));
    }

    @Test
    @DisplayName("缺省解释器应是交给 PATH 解析的 node，脚本根目录默认 scripts/node")
    void start_should_useNodeDefaults_when_noConfigurationIsGiven() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        plugin.start(context);

        assertEquals(NodeBridgePlugin.DEFAULT_INTERPRETER, plugin.config().interpreterPath());
        assertEquals(Paths.get(NodeBridgePlugin.DEFAULT_SCRIPTS_ROOT).toAbsolutePath().normalize(),
                plugin.config().scriptsRoot());
        assertEquals(NodeLanguage.ID, plugin.language().id());
    }

    @Test
    @DisplayName("解释器写成空白应报错，而不是静默退回 node")
    void start_should_rejectBlankInterpreter_when_nodePathIsBlank() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(NodeBridgePlugin.KEY_INTERPRETER, "   ");
        when(context.configuration()).thenReturn(configuration);
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        assertThrows(JellyfishException.class, () -> plugin.start(context));
    }

    @Test
    @DisplayName("别的语言的解释器键不该影响 Node 的解析")
    void start_should_ignoreForeignInterpreterKey_when_pythonPathIsPresent() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put("pythonPath", "/usr/local/bin/python3");
        when(context.configuration()).thenReturn(configuration);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        plugin.start(context);

        assertEquals(NodeBridgePlugin.DEFAULT_INTERPRETER, plugin.config().interpreterPath());
    }

    @Test
    @DisplayName("未启动时语言适配与配置都应为空")
    void accessors_should_beNull_when_pluginWasNeverStarted() {
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        assertNull(plugin.language());
        assertNull(plugin.config());
    }

    @Test
    @DisplayName("stop 应释放适配与配置，使下一次 start 重建")
    void stop_should_releaseLanguageAndConfig_when_calledAfterStart() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        NodeBridgePlugin plugin = new NodeBridgePlugin();
        plugin.start(context);

        plugin.stop();

        assertNull(plugin.language());
        assertNull(plugin.config());
        assertEquals(0, plugin.ledger().scriptCount());
        assertTrue(plugin.ledger().issues().isEmpty());
    }

    @Test
    @DisplayName("未启动就 stop 应是安全空操作")
    void stop_should_beNoOp_when_pluginWasNeverStarted() {
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        plugin.stop();

        assertNull(plugin.language());
    }

    @Test
    @DisplayName("start 应建立经熔断的调用入口，stop 应释放它")
    void caller_should_beCreatedOnStart_andReleasedOnStop() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        NodeBridgePlugin plugin = new NodeBridgePlugin();

        plugin.start(context);
        assertNotNull(plugin.caller(), "转发闭包必须经过熔断：注册时用的是它，而不是网关本身");
        assertNotNull(plugin.gateway());
        assertTrue(plugin.caller().states().isEmpty());

        plugin.stop();

        assertNull(plugin.caller());
        assertNull(plugin.gateway());
    }
}
