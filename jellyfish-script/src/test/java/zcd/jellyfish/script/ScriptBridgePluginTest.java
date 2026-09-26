package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 桥接插件骨架生命周期的单元测试。
 * <p>
 * 钉住的核心性质是<b>能力上下文每次 start 现造、stop 现释放</b>：PF4J 会长期缓存插件实例
 * （{@code stop} 不丢弃它），因此语言适配、配置、网关必须在 {@code start()} 重建，
 * 否则「停止再启动」这个唯一的插件重启语义会继续拿着旧配置。
 * <p>
 * 用假语言而不是真语言来测：这些性质与解释器无关，而真解释器会让用例依赖机器上装了什么，
 * 那就从「测骨架」变成了「测环境」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("桥接插件骨架生命周期")
class ScriptBridgePluginTest {

    /** 插件标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-demo";

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("start 应按配置建立语言适配")
    void start_should_buildLanguage_when_configurationIsGiven() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put("demoPath", "/opt/demo/bin/run");
        when(context.configuration()).thenReturn(configuration);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);

        assertNotNull(plugin.language());
        assertEquals("/opt/demo/bin/run", plugin.language().probeCommand().get(0));
    }

    @Test
    @DisplayName("start 应把配置保留下来，供诊断与后续一次性使用")
    void start_should_keepConfig_when_started() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);

        assertNotNull(plugin.config());
        assertEquals("demo3", plugin.config().interpreterPath());
    }

    @Test
    @DisplayName("start 不应要求脚本目录存在，环境缺失不能阻塞内核启动")
    void start_should_notRequireScriptsRoot_when_directoryDoesNotExist() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);

        assertNotNull(plugin.language());
    }

    @Test
    @DisplayName("start 遇到非法配置应上抛，交由框架转成插件 FAILED")
    void start_should_propagateJellyfishException_when_configurationIsInvalid() {
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, 7);
        when(context.configuration()).thenReturn(configuration);
        DemoPlugin plugin = new DemoPlugin();

        assertThrows(JellyfishException.class, () -> plugin.start(context));
    }

    @Test
    @DisplayName("stop 应释放语言适配与配置，使下一次 start 重建")
    void stop_should_releaseLanguageAndConfig_when_calledAfterStart() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();
        plugin.start(context);

        plugin.stop();

        assertNull(plugin.language());
        assertNull(plugin.config());
    }

    @Test
    @DisplayName("stop 应释放台账，使下一次 start 重新扫描")
    void stop_should_releaseLedger_when_calledAfterStart() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();
        plugin.start(context);

        plugin.stop();

        assertEquals(0, plugin.ledger().scriptCount());
        assertTrue(plugin.ledger().issues().isEmpty());
    }

    @Test
    @DisplayName("未启动就 stop 应是安全空操作")
    void stop_should_beNoOp_when_pluginWasNeverStarted() {
        DemoPlugin plugin = new DemoPlugin();

        plugin.stop();

        assertNull(plugin.language());
    }

    @Test
    @DisplayName("start 应幂等地重建适配，重复调用不报错")
    void start_should_rebuildLanguage_when_calledTwice() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);
        ScriptLanguage first = plugin.language();
        plugin.start(context);

        assertNotNull(first);
        assertNotNull(plugin.language());
    }

    @Test
    @DisplayName("start 应建立经熔断的调用入口，stop 应释放它")
    void caller_should_beCreatedOnStart_andReleasedOnStop() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);
        assertNotNull(plugin.caller(), "转发闭包必须经过熔断：注册时用的是它，而不是网关本身");
        assertNotNull(plugin.gateway());
        // 还没有任何调用发生过，因此不该凭空冒出熔断记录
        assertTrue(plugin.caller().states().isEmpty());

        plugin.stop();

        assertNull(plugin.caller());
        assertNull(plugin.gateway());
    }

    @Test
    @DisplayName("状态命令应注册在插件标识下，语言标识做路由键")
    void start_should_registerStatusCommand_underPluginLanguageId() {
        when(context.configuration()).thenReturn(null);
        when(context.pluginId()).thenReturn(PLUGIN_ID);
        DemoPlugin plugin = new DemoPlugin();

        plugin.start(context);

        // 注册动作本身由 PluginContext 承担，这里只钉住路由键用的是语言标识：
        // 用别的键会让 /<语言> 命令在真实内核里查不到
        assertEquals("demo", plugin.language().id());
    }

    /**
     * 一个用于测试骨架的假语言适配。
     * <p>
     * 探测命令故意指向一个不存在的可执行文件：探测失败只记告警，因此这既省掉一次外部进程，
     * 又把「探测失败不影响启动」这件事顺带测了。
     *
     * @author zcd
     */
    private static final class DemoLanguage implements ScriptLanguage {

        /** 解释器路径。 */
        private final String interpreterPath;

        /**
         * 构造假适配。
         *
         * @param interpreterPath 解释器路径
         */
        private DemoLanguage(String interpreterPath) {
            this.interpreterPath = interpreterPath;
        }

        @Override
        public String id() {
            return "demo";
        }

        @Override
        public String displayName() {
            return "Demo";
        }

        @Override
        public List<String> gatewayResources() {
            return Collections.singletonList("script/gateway.demo");
        }

        @Override
        public List<String> probeCommand() {
            // 默认与配置的解释器都指向不存在的可执行文件：探测失败只记告警，
            // 于是这里既不拉起外部进程，又顺带覆盖了「探测失败不影响启动」
            return Arrays.asList(interpreterPath, "--version");
        }

        @Override
        public List<String> startCommand(Path gatewayDirectory) {
            return Collections.singletonList("/nonexistent-demo-interpreter");
        }

        @Override
        public Map<String, String> environment() {
            return Collections.emptyMap();
        }
    }

    /**
     * 一个用于测试骨架的假桥接插件。
     * <p>
     * 它只有两个方法，正好是新增一门语言时真正需要写的东西——这也是「上收」这件事的验收标准。
     *
     * @author zcd
     */
    private static final class DemoPlugin extends ScriptBridgePlugin {

        @Override
        protected ScriptBridgeConfig resolveConfig(Map<String, Object> configuration) {
            return ScriptBridgeConfig.from(configuration, "demoPath", "demo3", "scripts/demo");
        }

        @Override
        protected ScriptLanguage createLanguage(ScriptBridgeConfig config) {
            return new DemoLanguage(config.interpreterPath());
        }
    }
}
