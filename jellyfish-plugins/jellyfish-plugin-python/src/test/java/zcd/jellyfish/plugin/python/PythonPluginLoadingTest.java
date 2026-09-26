package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.script.ScriptBridgeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python 桥接插件的加载链路端到端测试：用真实的 {@code plugin.properties} 与真实的插件类
 * 跑一遍「加载 → 描述符体检 → 启动 → 停止」。
 * <p>
 * <b>为什么要这么测</b>：单元测试只能证明「{@code start()} 里的逻辑对」，证明不了这个 jar 真的能被内核加载——
 * 描述符少一个键、插件包自带内核契约（{@code zcd/jellyfish/api} 或 {@code org/pf4j}）、
 * 入口类没有公开无参构造器，任何一项出错都会让插件在运行时悄无声息地不生效，而单元测试全绿。
 * <p>
 * <b>这里顺带钉住本方案最重要的一条性质</b>：启动期<b>不要求 Python 存在、也不拉起任何进程</b>。
 * 测试环境里 {@code scripts/python} 目录并不存在，插件依然必须启动成功——这正是
 * 「Python 环境损坏不阻塞内核启动」的机器可验证形式。
 *
 * @author zcd
 */
@DisplayName("Python 桥接插件加载链路")
class PythonPluginLoadingTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-plugin-python";

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 脚本根目录。 */
    @TempDir
    Path scriptsRoot;

    /** 网关资源抽取根目录。 */
    @TempDir
    Path gatewayRoot;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道，插件上下文装配需要。 */
    private EventChannel eventChannel;

    /** 被测插件管理器。 */
    private PF4JPluginManager manager;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        eventChannel = new EventChannel(EventChannelOptions.defaults(), registry);
        eventChannel.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        eventChannel.close();
    }

    @Test
    @DisplayName("真实描述符应让插件启动成功")
    void bootstrap_should_startPlugin_when_descriptorIsValid() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
    }

    @Test
    @DisplayName("脚本目录不存在时插件仍应启动成功，不拉起任何 Python 进程")
    void bootstrap_should_startPlugin_when_scriptsRootIsMissing() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();

        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));
        // 前提检查：默认脚本根目录（相对进程 cwd）确实不存在，本用例才真的在测「目录缺失也能启动」。
        // 这里用插件自己声明的那个默认值去算，而不是抄一份字面量——抄的那份会在默认值改动后
        // 继续通过，于是用例悄悄退化成「测了一个不存在的场景」
        assertTrue(Files.notExists(
                        Paths.get(PythonBridgePlugin.DEFAULT_SCRIPTS_ROOT).toAbsolutePath().normalize()),
                "测试前提不成立：默认脚本根目录意外存在");
    }

    @Test
    @DisplayName("关闭插件运行时后应不再报告任何已启动插件")
    void close_should_releaseStartedPlugins_when_pluginRuntimeIsClosed() throws IOException {
        installPlugin();

        manager = newManager();
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf(PLUGIN_ID));

        // 先接管引用再置空，避免 tearDown 对同一个管理器重复关闭
        PF4JPluginManager closing = manager;
        manager = null;
        closing.close();

        assertTrue(closing.plugins().isEmpty());
    }

    @Test
    @DisplayName("脚本清单应被注册为真实可路由的能力，且挂在脚本自己的命名空间下")
    void bootstrap_should_registerScriptCapabilities_when_manifestIsValid() throws IOException {
        installPlugin();
        writeScript("jira", "{\"entry\":\"main.py\","
                + "\"tools\":[{\"name\":\"jira_issue\",\"description\":\"读或改\",\"readOnly\":true}],"
                + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"},\"hasOptions\":true}],"
                + "\"contributions\":[\"prompt\"]}");

        manager = newManager(scriptsRoot.toString());
        manager.bootstrap();

        // 工具与命令注册在脚本自己的命名空间下，诊断输出能指出「是谁提供的」
        assertEquals("jellyfish-plugin-python::jira", ownerOfTool("jira_issue"));
        assertEquals("jellyfish-plugin-python::jira", ownerOfCommand("jira"));

        ToolDescriptor descriptor = extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class).get(0);
        assertEquals("jira_issue", descriptor.getName());
        assertTrue(descriptor.isReadOnly());

        // 类型级贡献按类型注册
        assertEquals(1, extensions.handlers(
                zcd.jellyfish.api.extension.PromptContributionRequest.class, null).size());
    }

    @Test
    @DisplayName("桥接插件自己的 /python 状态命令应注册在插件标识下，不进脚本命名空间")
    void bootstrap_should_registerStatusCommandUnderBridgePluginId() throws IOException {
        installPlugin();

        manager = newManager(scriptsRoot.toString());
        manager.bootstrap();

        assertEquals("jellyfish-plugin-python", extensions.bindings(CommandRequest.class, "python")
                .get(0).getOwner());
        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null));
        assertTrue(result.getOutput().contains("脚本 0 个"), result.getOutput());
    }

    @Test
    @DisplayName("启动期不应抽取网关资源，也不应创建任何 Python 进程")
    void start_should_notTouchProcessOrResources() throws IOException {
        // 这是本方案最核心的一条性质：注册来自磁盘上的清单，因此「Python 没装」
        // 或「主目录不可写」都不影响内核启动，工具清单依然完整。
        // 用「网关资源目录根本没被创建」来钉住它——比断言某个内部字段更接近用户能观察到的事实，
        // 而且一旦有人在 start() 里碰了进程，这里会先红
        installPlugin();
        writeScript("jira", "{\"entry\":\"main.py\",\"tools\":[{\"name\":\"jira_issue\"}]}");

        manager = newManager(scriptsRoot.toString());
        manager.bootstrap();

        assertEquals(1, extensions.handlers(ToolCallRequest.class, "jira_issue").size());
        assertFalse(Files.exists(gatewayRoot.resolve(PythonLanguage.ID)),
                "启动期不应抽取网关资源");
    }

    @Test
    @DisplayName("关闭运行时后脚本注册应随命名空间一起被回收")
    void close_should_reclaimScriptRegistrations_when_runtimeIsClosed() throws IOException {
        installPlugin();
        writeScript("jira", "{\"entry\":\"main.py\",\"tools\":[{\"name\":\"jira_issue\"}]}");
        manager = newManager(scriptsRoot.toString());
        manager.bootstrap();
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "jira_issue").size());

        PF4JPluginManager closing = manager;
        manager = null;
        closing.close();

        assertTrue(extensions.handlers(ToolCallRequest.class, "jira_issue").isEmpty());
        assertTrue(extensions.handlers(CommandRequest.class, "python").isEmpty());
    }

    /**
     * 取某个工具的注册来源。
     *
     * @param toolName 工具名
     * @return owner
     */
    private String ownerOfTool(String toolName) {
        return registry.resolve(ToolCallRequest.class, toolName).get(0).getOwner();
    }

    /**
     * 取某条命令的注册来源。
     *
     * @param commandName 命令名
     * @return owner
     */
    private String ownerOfCommand(String commandName) {
        return registry.resolve(CommandRequest.class, commandName).get(0).getOwner();
    }

    /**
     * 在脚本根目录下写一个脚本。
     *
     * @param id       目录名与脚本标识
     * @param manifest 清单正文
     * @throws IOException 写入失败时抛出
     */
    private void writeScript(String id, String manifest) throws IOException {
        Path directory = scriptsRoot.resolve(id);
        Files.createDirectories(directory);
        Files.write(directory.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("main.py"), new byte[0]);
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 写入失败时抛出
     */
    private void installPlugin() throws IOException {
        Path pluginDir = pluginsRoot.resolve(PLUGIN_ID);
        Files.createDirectories(pluginDir);
        try (InputStream descriptor = getClass().getResourceAsStream("/plugin.properties")) {
            if (descriptor == null) {
                throw new IllegalStateException("测试类路径上找不到 plugin.properties");
            }
            Files.copy(descriptor, pluginDir.resolve("plugin.properties"));
        }
    }

    /**
     * 构造扫描临时目录的插件管理器。
     *
     * @return 插件管理器
     */
    private PF4JPluginManager newManager() {
        return newManager(null);
    }

    /**
     * 构造扫描临时目录的插件管理器，并可注入脚本根目录配置。
     *
     * @param scriptsRootConfig 脚本根目录配置，为 {@code null} 时不给配置段
     * @return 插件管理器
     */
    private PF4JPluginManager newManager(String scriptsRootConfig) {
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        // 抽取根目录指到临时目录：断言「启动期没抽取过」需要一个自己盯着的目录，
        // 否则用例会去看用户主目录（那既依赖环境也污染环境）
        python.put(ScriptBridgeConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        if (scriptsRootConfig != null) {
            python.put(ScriptBridgeConfig.KEY_SCRIPTS_ROOT, scriptsRootConfig);
        }
        configurations.put(PLUGIN_ID, python);
        PluginContextFactory contexts = new PluginContextFactory(extensions, eventChannel, registry);
        return new PF4JPluginManager(contexts, new PluginRuntimeConfig(
                java.util.Collections.singletonList(pluginsRoot), null, null, configurations));
    }
}
