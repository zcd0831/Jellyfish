package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolOutputSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行插件的端到端测试：真的 fork {@code /bin/sh} 执行真的命令。
 * <p>
 * <b>为什么不进 {@code mvn test}</b>：它依赖真实的 {@code /bin/sh} 与 {@code pgrep}，
 * 并且刻意让进程跑起来再杀掉——这些都不是单元测试该做的事（AGENTS.md 规定单测不访问外部资源）。
 * 因此它在 {@code -Pshell-it} 里跑。
 * <p>
 * <b>它测的是单测测不到的那一半</b>：管道是否真的被排空、子进程是否真的被杀掉、
 * 输出是否在命令退出后还能被完整取回。这些性质用假进程验证不了。
 * <p>
 * <b>超时预算刻意压得很小</b>（1~2 秒），好让整套用例几秒内跑完；因此断言的是
 * 「终止发生了」而不是「终止有多快」。
 *
 * @author zcd
 */
@DisplayName("命令行插件端到端")
class ShellIT {

    /** 测试用工作目录。 */
    @TempDir
    Path workingDirectory;

    @Test
    @DisplayName("echo 的输出与退出码都能取回")
    void run_should_captureOutputAndExitCode() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("echo 你好", sink, CancellationToken.NONE);

        assertTrue(output.contains("exit: 0"), output);
        assertTrue(sink.body().contains("你好"), sink.body());
    }

    @Test
    @DisplayName("stderr 与 stdout 合并进同一条流")
    void run_should_mergeStderrIntoStdout() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("echo 正常; echo 出错 1>&2", sink, CancellationToken.NONE);

        assertTrue(sink.body().contains("正常"), sink.body());
        assertTrue(sink.body().contains("出错"), sink.body());
        assertTrue(output.contains("exit: 0"), output);
    }

    @Test
    @DisplayName("非零退出码如实上报，不抛异常")
    void run_should_reportNonZeroExitCode() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("exit 3", sink, CancellationToken.NONE);

        assertTrue(output.contains("exit: 3"), output);
    }

    @Test
    @DisplayName("cwd 参数决定命令在哪跑")
    void run_should_runInWorkingDirectory() throws IOException {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        run(args("command", "pwd", "cwd", workingDirectory.toString()), sink, CancellationToken.NONE);

        // 用规范路径比较：macOS 上 /tmp 是指向 /private/tmp 的符号链接，而 shell 报的是物理路径
        assertTrue(sink.body().contains(workingDirectory.toFile().getCanonicalPath()), sink.body());
    }

    @Test
    @DisplayName("多行大输出被完整捕获（管道必须持续排空）")
    void run_should_captureLargeOutput() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("seq 1 2000", sink, CancellationToken.NONE);

        assertTrue(sink.body().contains("2000"), "最后一行必须还在，说明管道被排空了");
        assertTrue(output.contains("exit: 0"), output);
    }

    @Test
    @DisplayName("墙钟超时会杀掉真的在跑的进程")
    @Timeout(30)
    void run_should_killRealProcess_whenTimeout() throws Exception {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        Map<String, Object> arguments = args("command", "sleep 30", "timeout_seconds", 1);

        String output = run(arguments, sink, CancellationToken.NONE);

        assertTrue(output.contains("已超时"), output);
        assertTrue(awaitsNoProcess("sleep 30", 5000L), "超时后不应留下 sleep 进程");
    }

    @Test
    @DisplayName("取消令牌会杀掉真的在跑的进程")
    @Timeout(30)
    void run_should_killRealProcess_whenCancelled() throws Exception {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ManualToken token = new ManualToken();
        Thread canceller = new Thread(() -> {
            sleep(700L);
            token.cancel();
        });
        canceller.start();

        String output = run(args("command", "sleep 30"), sink, token);
        canceller.join();

        assertTrue(output.contains("已取消"), output);
        assertTrue(awaitsNoProcess("sleep 30", 5000L), "取消后不应留下 sleep 进程");
    }

    @Test
    @DisplayName("静默超时：持续无输出即判定卡住")
    @Timeout(30)
    void run_should_killRealProcess_whenIdleTimeout() throws Exception {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        ShellResult result = new ShellProcessRunner(new CommonsExecShellProcessLauncher())
                .run(invocation("sleep 30", 30_000L, 1_000L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.IDLE_TIMEOUT, result.termination());
        assertTrue(awaitsNoProcess("sleep 30", 5000L), "静默超时后不应留下 sleep 进程");
    }

    @Test
    @DisplayName("一直有输出的命令不会触发静默超时")
    @Timeout(30)
    void run_should_notKillProcess_whenOutputKeepsComing() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        ShellResult result = new ShellProcessRunner(new CommonsExecShellProcessLauncher())
                .run(invocation("for i in 1 2 3 4 5; do echo $i; sleep 0.4; done", 20_000L, 1_000L),
                        sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.COMPLETED, result.termination());
        assertTrue(sink.body().contains("5"), sink.body());
    }

    @Test
    @DisplayName("交互式命令因为 stdin 已关闭而立刻失败，而不是等在那里")
    @Timeout(30)
    void run_should_failFast_whenCommandReadsStdin() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("cat", sink, CancellationToken.NONE);

        assertTrue(output.contains("exit: 0"), output);
        assertEquals("", sink.body().trim());
    }

    @Test
    @DisplayName("二进制输出不进正文")
    void run_should_notFeedBinaryIntoContext() {
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        String output = run("head -c 512 /dev/urandom", sink, CancellationToken.NONE);

        assertEquals("", sink.body().trim());
        assertTrue(output.contains("字节已省略"), output);
    }

    /**
     * 用默认配置执行一条命令并取回输出文本。
     *
     * @param command 命令原文
     * @param sink    捕获通道
     * @param token   取消令牌
     * @return 工具输出
     */
    private String run(String command, ToolOutputSink sink, CancellationToken token) {
        return run(args("command", command), sink, token);
    }

    /**
     * 用默认配置执行一条命令并取回输出文本。
     *
     * @param arguments 工具参数
     * @param sink      捕获通道
     * @param token     取消令牌
     * @return 工具输出
     */
    private String run(Map<String, Object> arguments, ToolOutputSink sink, CancellationToken token) {
        ShellTool tool = new ShellTool(PluginConfig.from(null),
                new ShellProcessRunner(new CommonsExecShellProcessLauncher()));
        ToolCallResult result = tool.handle(new ToolCallRequest(ShellTool.TOOL_NAME, arguments, null, token, sink));
        return String.valueOf(result.getOutput());
    }

    /**
     * 构造调用参数（cwd 固定为临时目录）。
     *
     * @param command            命令原文
     * @param timeoutMillis      墙钟超时
     * @param idleTimeoutMillis  静默超时
     * @return 调用参数
     */
    private ShellInvocation invocation(String command, long timeoutMillis, long idleTimeoutMillis) {
        return new ShellInvocation(command, workingDirectory, timeoutMillis, idleTimeoutMillis,
                ShellEnvironment.build(System.getenv(), ShellEnvironment.sensitivePatterns(null), null));
    }

    /**
     * 等待某个命令行模式从进程表里消失。
     *
     * @param pattern  pgrep -f 的模式
     * @param budgetMs 最长等待毫秒数
     * @return 已消失返回 {@code true}
     * @throws IOException          查询失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean awaitsNoProcess(String pattern, long budgetMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (!processExists(pattern)) {
                return true;
            }
            Thread.sleep(100L);
        }
        return !processExists(pattern);
    }

    /**
     * 查询匹配模式的进程是否存在。
     *
     * @param pattern 模式
     * @return 存在返回 {@code true}
     * @throws IOException          查询失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean processExists(String pattern) throws IOException, InterruptedException {
        Process pgrep = new ProcessBuilder("pgrep", "-f", pattern).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        pgrep.waitFor(5, TimeUnit.SECONDS);
        return output.length() > 0;
    }

    /**
     * 睡眠指定毫秒数。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /**
     * 可手动取消的令牌。
     *
     * @author zcd
     */
    private static final class ManualToken implements CancellationToken {

        /** 是否已取消。 */
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 取消回调。 */
        private volatile Runnable callback;

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void onCancel(Runnable action) {
            callback = action;
            if (cancelled.get() && action != null) {
                action.run();
            }
        }

        /**
         * 触发取消。
         */
        void cancel() {
            cancelled.set(true);
            Runnable action = callback;
            if (action != null) {
                action.run();
            }
        }
    }


}
