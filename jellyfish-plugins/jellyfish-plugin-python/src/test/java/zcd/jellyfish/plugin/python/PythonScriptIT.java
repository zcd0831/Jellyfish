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
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Python 的端到端测试：注册 → 调用 → 超时隔离 → 关闭。
 * <p>
 * <b>为什么不进 {@code mvn test}</b>：它需要一个真实的解释器，而 AGENTS.md 规定单元测试不访问
 * 外部资源。因此它命名为 {@code *IT}（Surefire 默认的包含规则只认 {@code *Test}），
 * 由 {@code -Pscript-it} profile 显式启用。这一步提前到 P4 落地，是因为网关、worker 与 SDK
 * 都是「没有测试守着就会悄悄坏掉」的代码——它们唯一能被验证的方式就是真的跑一次。
 * <p>
 * 机器上没装解释器时整类跳过（{@code assumeTrue}），而不是失败：环境缺失与代码有 bug 是两件事。
 *
 * @author zcd
 */
@DisplayName("Python 脚本端到端")
class PythonScriptIT {

    /** 解释器探测超时。 */
    private static final long PROBE_TIMEOUT_SECONDS = 5L;

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 脚本根目录。 */
    @TempDir
    Path scriptsRoot;

    /** 网关资源抽取根目录。 */
    @TempDir
    Path gatewayRoot;

    /** 插件管理器。 */
    private PF4JPluginManager manager;

    /** 共用注册表：注册与调用必须落在同一份上，否则测试会「注册成功但调用找不到」。 */
    private final zcd.jellyfish.infra.registry.TypeRegistry registry =
            new zcd.jellyfish.infra.registry.TypeRegistry();

    /** 同步扩展点策略。 */
    private final zcd.jellyfish.infra.extension.ExtensionRegistry extensions =
            new zcd.jellyfish.infra.extension.ExtensionRegistry(registry);

    /** 事件通道。 */
    private final zcd.jellyfish.infra.event.EventChannel events =
            new zcd.jellyfish.infra.event.EventChannel(
                    zcd.jellyfish.infra.event.EventChannelOptions.defaults(), registry);

    /**
     * 跳过没有解释器的环境。
     */
    @BeforeEach
    void requirePython() {
        assumeTrue(interpreterAvailable(), "本机没有可用的 python3，跳过端到端测试");
    }

    /**
     * 关闭运行时，确保子进程不残留。
     */
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
    }

    @Test
    @DisplayName("工具应被真实执行，结果与脚本返回一致")
    void tool_should_returnScriptOutput_when_invokedEndToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "PROJ-1"));

        assertEquals("issue PROJ-1 处于 OPEN（会话 s-1）", result.getOutput());
    }

    @Test
    @DisplayName("脚本抛出的异常应变成可读的调用失败，而不是静默的空结果")
    void tool_should_failWithMessage_when_scriptRaises() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("缺少参数 key"), failure.getMessage());
    }

    @Test
    @DisplayName("类型级贡献应能按类型调用")
    void contribution_should_beInvoked_byType() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        zcd.jellyfish.api.extension.PromptContribution contribution =
                extensions.invoke(extensions.handler(PromptContributionRequest.class, null),
                        new PromptContributionRequest("s-1"));

        assertEquals("Jira：会话 s-1 有 3 个未读", contribution.getText());
    }

    @Test
    @DisplayName("命令应被真实执行，结果带命令名与输出")
    void command_should_beInvoked_endToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "jira"),
                new CommandRequest("jira", null, "s-1"));

        assertEquals("已切换 PROJ-1", result.getOutput());
    }

    @Test
    @DisplayName("卡死的脚本应在超时后被隔离，且下一次调用能重新拉起")
    void tool_should_beIsolatedAfterTimeout_thenRecover() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        // 卡死的调用必须在超时后失败——而不是永远挂着（那会把 ReAct 回合也一起拖住）。
        // 两侧的超时值相同，因此**谁先发现就由谁报**：宿主可能报本地超时，
        // 也可能先收到网关的「已隔离」。这个不确定性是设计使然（而不是测试写得松），
        // 它会在 P5 落地「宿主超时 → 主动 kill_worker」之后收敛成单一来源
        long started = System.currentTimeMillis();
        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));
        assertTrue(System.currentTimeMillis() - started >= 2000, "不应早于配置的超时返回");
        boolean timeoutReported = failure instanceof zcd.jellyfish.script.protocol.ScriptTimeoutException
                || failure.getMessage().contains("隔离");
        assertTrue(timeoutReported, failure.getMessage());

        // 隔离只针对该脚本的那一代 worker：重新拉起之后调用应当恢复。
        // 这里给几秒重试窗口而不是立刻断言，因为「隔离」是两段式的：请求先被拒绝，
        // 卡死的 worker 随后被终止（它可能正卡在不响应信号的系统调用里），
        // 新 worker 就绪之前的那一小段时间里调用仍会失败——这是**已实现行为**，不是抖动
        ToolCallResult recovered = invokeUntilSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "X"), 15_000L);
        assertEquals("issue X 处于 OPEN（会话 s-1）", recovered.getOutput());
    }

    @Test
    @DisplayName("清单与实现不一致时脚本应拒绝服务，且错误可归因为清单问题")
    void script_should_refuseService_when_manifestLies() throws IOException {
        // 清单里多声明了一个代码没有的工具：这正是「模型按不存在的定义去调用」的成因
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST.replace(
                "\"tools\":[", "\"tools\":[{\"name\":\"jira_nonexistent\"},"));
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_nonexistent", Collections.<String, Object>emptyMap()));

        // 错误必须点名「哪个脚本、差在哪一项」：只说「初始化失败」等于把定位工作推回给用户，
        // 而这两条信息此刻正好都在网关手里
        assertTrue(failure.getMessage().contains("jira_nonexistent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tools"), failure.getMessage());
    }

    @Test
    @DisplayName("状态命令应列出脚本与已登记能力")
    void statusCommand_should_listScripts() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null));

        assertTrue(result.getOutput().contains("脚本 1 个"), result.getOutput());
        assertTrue(result.getOutput().contains("运行时"), result.getOutput());
    }


    @Test
    @DisplayName("空闲的 worker 应自行退场，进程数回到只剩网关")
    void worker_should_selfDestruct_when_idle() throws IOException, InterruptedException {
        // 「空闲时进程数回零」是本方案明确承诺的性质（懒启动 + 自毁），而它依赖 worker 与网关
        // 两侧各自的周期检查。这里用操作系统的进程表直接验证，而不是看日志——
        // 后者只能证明「它说要退场」，前者才能证明「它真的退了」
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntimeWithIdle(1);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        assertTrue(awaitProcessCount(gatewayDirectory, 2), "调用之后应有一个 worker 在跑");
        assertTrue(awaitProcessCount(gatewayDirectory, 1),
                "空闲超过配置时长后 worker 应自行退场，实际仍有 "
                        + countProcesses(gatewayDirectory) + " 个进程");
    }

    /**
     * 用默认超时启动插件，并指定 worker 空闲自毁秒数。
     *
     * @param idleSeconds worker 空闲自毁秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithIdle(int idleSeconds) throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(PythonConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(PythonConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonConfig.KEY_PYTHON_PATH, interpreter());
        python.put(PythonConfig.KEY_WORKER_IDLE, Integer.valueOf(idleSeconds));
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(extensions, events, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
        manager.bootstrap();
    }

    /**
     * 数一数某个网关目录下还有几个进程（网关自己 + 它的 worker，二者的命令行相同）。
     *
     * @param gatewayDirectory 网关资源目录
     * @return 进程数
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int countProcesses(java.nio.file.Path gatewayDirectory)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ps", "-eo", "command").redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        process.waitFor(5, TimeUnit.SECONDS);
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.contains(gatewayDirectory.toString())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 等到进程数落到期望值。
     *
     * @param gatewayDirectory 网关资源目录
     * @param expected         期望进程数
     * @return 在超时前到达期望值返回 {@code true}
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean awaitProcessCount(java.nio.file.Path gatewayDirectory, int expected)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (countProcesses(gatewayDirectory) == expected) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    /**
     * 读完一个输入流。
     *
     * @param stream 输入流
     * @return 字节内容
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** 供 {@code statusCommand} 断言用的示例脚本。 */
    private static final String TOOL_SCRIPT = ""
            + "from jellyfish_sdk import tool, command, contributes, ScriptError\n"
            + "\n"
            + "@tool(name=\"jira_issue\", description=\"读 issue\", read_only=True)\n"
            + "def jira_issue(args, ctx):\n"
            + "    if not args.get(\"key\"):\n"
            + "        raise ScriptError(\"缺少参数 key\")\n"
            + "    return \"issue %s 处于 OPEN（会话 %s）\" % (args[\"key\"], ctx.session_id)\n"
            + "\n"
            + "@tool(name=\"jira_hang\", description=\"卡死\")\n"
            + "def jira_hang(args, ctx):\n"
            + "    import time\n"
            + "    time.sleep(600)\n"
            + "    return \"never\"\n"
            + "\n"
            + "@command(\"jira\", summary=\"操作 Jira\")\n"
            + "def jira(tokens, raw, ctx):\n"
            + "    return \"已切换 PROJ-1\"\n"
            + "\n"
            + "@contributes(\"prompt\")\n"
            + "def prompt(ctx):\n"
            + "    return \"Jira：会话 %s 有 3 个未读\" % ctx.session_id\n";

    /**
     * 与 {@link #TOOL_SCRIPT} 逐字对应的清单。
     * <p>
     * 两者必须一起改：这正是「清单与实现必须一致」这条约束在测试里的样子——
     * 用例一旦用一份过时的清单，脚本会直接拒绝服务，而错误信息会立刻指出差在哪一项。
     */
    private static final String FULL_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"jira_issue\",\"readOnly\":true},{\"name\":\"jira_hang\"}],"
            + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"}}],"
            + "\"contributions\":[\"prompt\"]}";

    /**
     * 启动插件并完成注册。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds) throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(PythonConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(PythonConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        python.put(PythonConfig.KEY_PYTHON_PATH, interpreter());
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(extensions, events, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("jellyfish-plugin-python"));
    }

    /**
     * 用默认超时启动插件。
     *
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime() throws IOException {
        startRuntime(5);
    }

    /**
     * 调用一个工具。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @return 调用结果
     */
    private ToolCallResult invokeTool(String toolName, Map<String, Object> arguments) {
        return extensions.invoke(extensions.handler(ToolCallRequest.class, toolName),
                new ToolCallRequest(toolName, arguments, "s-1"));
    }

    /**
     * 反复调用直到成功，用于容忍「worker 正在被替换」的短暂窗口。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @param timeoutMs 总等待上限
     * @return 最后一次成功的结果
     */
    private ToolCallResult invokeUntilSucceeds(String toolName, Map<String, Object> arguments,
                                               long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JellyfishException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return invokeTool(toolName, arguments);
            } catch (JellyfishException e) {
                last = e;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new AssertionError("重试窗口内始终未恢复", last);
    }

    /**
     * 在脚本根目录下写一个脚本。
     *
     * @param id       脚本标识
     * @param source   入口源码
     * @param manifest 清单正文
     * @throws IOException 写入失败时抛出
     */
    private void writeScript(String id, String source, String manifest) throws IOException {
        Path directory = scriptsRoot.resolve(id);
        Files.createDirectories(directory);
        Files.write(directory.resolve("main.py"), source.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 复制失败时抛出
     */
    private void installPlugin() throws IOException {
        // 与 PythonPluginLoadingTest 同一套装载方式：PF4J 认「插件目录 + plugin.properties」，
        // 目录名必须是 plugin.id——这也是 PF4J 判定插件身份的依据
        Path directory = pluginsRoot.resolve("jellyfish-plugin-python");
        Files.createDirectories(directory);
        try (InputStream stream = PythonScriptIT.class.getResourceAsStream("/plugin.properties")) {
            assertTrue(stream != null, "测试类路径上找不到 plugin.properties");
            Files.copy(stream, directory.resolve("plugin.properties"));
        }
    }

    /**
     * 判断本机是否有可用的解释器。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean interpreterAvailable() {
        try {
            Process process = new ProcessBuilder(interpreter(), "--version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            process.destroyForcibly();
            return finished && code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 取解释器路径，允许用系统属性覆盖（例如指向虚拟环境里的解释器）。
     *
     * @return 解释器路径或命令名
     */
    private static String interpreter() {
        return System.getProperty("jellyfish.test.python", PythonConfig.DEFAULT_PYTHON_PATH);
    }

}
