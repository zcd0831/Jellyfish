package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShellTool} 的单元测试。
 * <p>
 * 工具层负责三件事，因此断言也分三处：参数与配置怎么合并（超时钳制、环境叠加）、
 * 工作目录怎么校验（提前报错而不是让 shell 报「命令找不到」）、
 * 以及结论怎么回灌（元数据行必须在正文首行）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShellTool")
class ShellToolTest {

    /** 测试用工作目录。 */
    @TempDir
    Path workingDirectory;

    @Test
    @DisplayName("元数据行出现在正文首行——模型永远能在同一个地方找到退出码")
    void handle_should_putSummaryOnFirstLine() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.write("构建成功\n");
        process.exitNow(0);

        String output = invoke(new ShellTool(PluginConfig.from(null), runner(launcher)),
                args("command", "make"));

        int firstLine = output.indexOf('\n');
        assertTrue(output.startsWith("cwd: "), output);
        assertTrue(firstLine > 0, output);
        assertTrue(output.substring(0, firstLine).contains("exit: 0"), output);
        assertTrue(output.endsWith("构建成功\n"), output);
    }

    @Test
    @DisplayName("非零退出码回灌为结果而不是异常")
    void handle_should_notThrow_when_exitCodeNonZero() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.exitNow(3);

        String output = invoke(new ShellTool(PluginConfig.from(null),
                runner(new ShellTestSupport.FakeLauncher(process))), args("command", "grep x file"));

        assertTrue(output.contains("exit: 3"), output);
    }

    @Test
    @DisplayName("cwd 不存在时提前报错，而不是让 shell 报「命令找不到」")
    void handle_should_fail_when_cwdMissing() {
        Path missing = workingDirectory.resolve("not-there");
        ShellTool tool = new ShellTool(PluginConfig.from(null),
                runner(new ShellTestSupport.FakeLauncher(new ShellTestSupport.FakeProcess())));

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> tool.handle(new ToolCallRequest(ShellTool.TOOL_NAME,
                        args("command", "ls", "cwd", missing.toString()))));

        assertTrue(failure.getMessage().contains("not-there"), failure.getMessage());
    }

    @Test
    @DisplayName("cwd 参数被解析成绝对路径并传给执行器")
    void handle_should_passWorkingDirectoryToRunner() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.exitNow(0);

        invoke(new ShellTool(PluginConfig.from(null), runner(launcher)),
                args("command", "ls", "cwd", workingDirectory.toString()));

        assertEquals(workingDirectory.toAbsolutePath().normalize(),
                launcher.invocations().get(0).workingDirectory());
    }

    @Test
    @DisplayName("未指定超时时用配置缺省值")
    void handle_should_useConfiguredTimeout_byDefault() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.exitNow(0);

        invoke(new ShellTool(PluginConfig.from(null), runner(launcher)), args("command", "ls"));

        assertEquals(PluginConfig.DEFAULT_TIMEOUT_SECONDS * 1000L, launcher.invocations().get(0).timeoutMillis());
    }

    @Test
    @DisplayName("模型给的超时被钳制在 maxTimeoutSeconds 之内，而不是被拒绝")
    void handle_should_clampRequestedTimeout() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.exitNow(0);

        invoke(new ShellTool(config(60, 300, 0), runner(launcher)),
                args("command", "ls", "timeout_seconds", 9999));

        assertEquals(300_000L, launcher.invocations().get(0).timeoutMillis());
    }

    @Test
    @DisplayName("静默超时来自配置，模型无法设置")
    void handle_should_takeIdleTimeoutFromConfig() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.exitNow(0);

        invoke(new ShellTool(config(60, 300, 45), runner(launcher)), args("command", "ls"));

        assertEquals(45_000L, launcher.invocations().get(0).idleTimeoutMillis());
    }

    @Test
    @DisplayName("环境变量：脱敏后叠加防挂死与用户配置")
    void handle_should_buildEnvironment() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        process.exitNow(0);

        invoke(new ShellTool(config(60, 300, 0), runner(launcher)), args("command", "env"));

        Map<String, String> environment = launcher.invocations().get(0).environment();
        // 父进程环境里不该出现任何凭据命名的变量
        for (String name : environment.keySet()) {
            assertTrue(!name.toLowerCase(java.util.Locale.ROOT).contains("token"), name);
            assertTrue(!name.toLowerCase(java.util.Locale.ROOT).contains("password"), name);
        }
        assertEquals("cat", environment.get("PAGER"));
    }

    @Test
    @DisplayName("结果要带上元数据：界面与审计靠字段判断「命令成没成」，不解析首行文案")
    void handle_should_carryMetadata() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.exitNow(3);
        ShellTool tool = new ShellTool(PluginConfig.from(null),
                runner(new ShellTestSupport.FakeLauncher(process)));

        ToolCallResult result = tool.handle(new ToolCallRequest(ShellTool.TOOL_NAME, args("command", "ls"),
                null, CancellationToken.NONE, new ShellTestSupport.RecordingSink()));

        assertEquals(Integer.valueOf(3), result.getMetadata().get(ToolMetadata.KEY_EXIT_CODE));
        assertEquals("COMPLETED", result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
        // 首行结论与字段同源：模型那条路一个字不变
        assertTrue(String.valueOf(result.getOutput()).startsWith("cwd: "), String.valueOf(result.getOutput()));
    }

    @Test
    @DisplayName("sink 是 NOOP 时也要让结论可见——否则模型分不清「没输出」与「没跑」")
    void handle_should_fallBackToSummary_whenSinkIsNoop() throws Exception {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.exitNow(0);
        ShellTool tool = new ShellTool(PluginConfig.from(null),
                runner(new ShellTestSupport.FakeLauncher(process)));

        Object output = tool.handle(new ToolCallRequest(ShellTool.TOOL_NAME, args("command", "ls"),
                null, CancellationToken.NONE, ToolOutputSink.NOOP)).getOutput();

        assertTrue(String.valueOf(output).contains("exit: 0"), String.valueOf(output));
    }

    /**
     * 调用工具并取回输出文本。
     *
     * @param tool      工具
     * @param arguments 参数
     * @return 输出文本
     */
    private static String invoke(ShellTool tool, Map<String, Object> arguments) {
        ToolCallResult result = tool.handle(new ToolCallRequest(ShellTool.TOOL_NAME, arguments, null,
                CancellationToken.NONE, new ShellTestSupport.RecordingSink()));
        return String.valueOf(result.getOutput());
    }

    /**
     * 用假启动器造执行器。
     *
     * @param launcher 假启动器
     * @return 执行器
     */
    private static ShellProcessRunner runner(ShellTestSupport.FakeLauncher launcher) {
        return new ShellProcessRunner(launcher);
    }

    /**
     * 构造指定超时配置的插件配置。
     *
     * @param timeoutSeconds     墙钟缺省秒数
     * @param maxTimeoutSeconds  墙钟上限秒数
     * @param idleTimeoutSeconds 静默秒数
     * @return 插件配置
     */
    private static PluginConfig config(int timeoutSeconds, int maxTimeoutSeconds, int idleTimeoutSeconds) {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put("timeoutSeconds", Integer.valueOf(timeoutSeconds));
        configuration.put("maxTimeoutSeconds", Integer.valueOf(maxTimeoutSeconds));
        configuration.put("idleTimeoutSeconds", Integer.valueOf(idleTimeoutSeconds));
        return PluginConfig.from(context(configuration));
    }

    /**
     * 造一个只返回配置段的插件上下文。
     *
     * @param configuration 配置段
     * @return 插件上下文
     */
    private static PluginContext context(Map<String, Object> configuration) {
        PluginContext context = mock(PluginContext.class);
        when(context.configuration()).thenReturn(configuration);
        return context;
    }

    /**
     * 构造参数映射。
     *
     * @param namesAndValues 参数名与值交替
     * @return 参数映射
     */
    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new HashMap<String, Object>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            arguments.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }
        return arguments;
    }
}
