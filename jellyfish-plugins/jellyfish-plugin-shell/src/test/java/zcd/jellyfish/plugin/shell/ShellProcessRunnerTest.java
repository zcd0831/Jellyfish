package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellProcessRunner} 的单元测试。
 * <p>
 * 这里钉住的是三条最容易写错的语义：
 * <ul>
 *     <li><b>非零退出码不是异常</b>：{@code grep} 返回 1 是信息，抛异常会把工具的失败语义稀释掉；</li>
 *     <li><b>两道计时器互相独立</b>：墙钟回答「最多跑多久」，静默回答「多久没动静就当死了」，
 *     静默必须在有输出时被推迟；</li>
 *     <li><b>取消之后进程必须死</b>：回调只发信号，完整的终止链由等待循环接手。</li>
 * </ul>
 * 全部用假进程驱动，不 fork 真实进程（见 {@link ShellTestSupport}）。
 *
 * @author zcd
 */
@DisplayName("ShellProcessRunner")
class ShellProcessRunnerTest {

    /** 测试用工作目录。 */
    private static final Path CWD = Paths.get("").toAbsolutePath();

    @Test
    @DisplayName("命令自己跑完时退出码如实上报，且不抛异常")
    void run_should_reportExitCode_withoutThrowing() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        process.write("匹配到 2 行\n");
        process.exitNow(1);

        ShellResult result = new ShellProcessRunner(launcher).run(invocation(5_000L, 0L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.COMPLETED, result.termination());
        assertTrue(result.summary(CWD.toString()).contains("exit: 1"), result.summary(CWD.toString()));
        assertFalse(result.isSuccess());
        assertEquals("匹配到 2 行\n", sink.body());
    }

    @Test
    @DisplayName("程序写入 sink 的内容与退出码都会被记录")
    void run_should_captureOutputInto_sink() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        process.write("第一行\n第二行\n");
        process.exitNow(0);

        ShellResult result = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(5_000L, 0L), sink, CancellationToken.NONE);

        assertTrue(result.isSuccess());
        assertEquals("第一行\n第二行\n", sink.body());
        assertTrue(sink.summaryText().isEmpty(), "元数据由工具层声明，执行器不该自己写");
    }

    @Test
    @DisplayName("墙钟超时会终止进程，并如实说明是超时而不是退出码")
    void run_should_killProcess_whenWallClockTimeout() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        ShellResult result = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(100L, 0L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.TIMEOUT, result.termination());
        assertEquals(1, process.destroys());
        assertFalse(process.isAlive());
        assertTrue(result.summary(CWD.toString()).contains("已超时"), result.summary(CWD.toString()));
    }

    @Test
    @DisplayName("静默超时：连续没有输出即判定卡住")
    void run_should_killProcess_whenIdleTimeout() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();

        ShellResult result = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(30_000L, 150L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.IDLE_TIMEOUT, result.termination());
        assertTrue(result.summary(CWD.toString()).contains("无输出"), result.summary(CWD.toString()));
    }

    @Test
    @DisplayName("持续有输出时静默计时器不会触发——长时间跑但一直在打印的命令不该被误杀")
    void run_should_notTriggerIdleTimeout_whenOutputKeepsComing() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellProcessRunner runner = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process));

        Thread writer = new Thread(() -> {
            for (int index = 0; index < 8; index++) {
                process.write("还在跑\n");
                sleep(80L);
            }
            process.exitNow(0);
        });
        writer.start();

        ShellResult result = runner.run(invocation(10_000L, 200L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.COMPLETED, result.termination());
        assertEquals(0, process.destroys());
    }

    @Test
    @DisplayName("静默超时关闭时不触发")
    void run_should_notTriggerIdleTimeout_whenDisabled() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellProcessRunner runner = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process));

        Thread slow = new Thread(() -> {
            sleep(400L);
            process.exitNow(0);
        });
        slow.start();

        ShellResult result = runner.run(invocation(10_000L, 0L), sink, CancellationToken.NONE);

        assertEquals(ShellResult.Termination.COMPLETED, result.termination());
    }

    @Test
    @DisplayName("取消令牌一旦取消，进程必须被终止且结果是「已取消」")
    void run_should_killProcess_whenCancelled() throws InterruptedException {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        ShellProcessRunner runner = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process));
        CancellationToken token = new TestCancellationToken();
        Thread canceller = new Thread(() -> {
            sleep(150L);
            ((TestCancellationToken) token).cancel();
        });
        canceller.start();

        ShellResult result = runner.run(invocation(30_000L, 0L), sink, token);
        canceller.join();

        assertEquals(ShellResult.Termination.CANCELLED, result.termination());
        assertFalse(process.isAlive());
        assertTrue(result.summary(CWD.toString()).contains("已取消"), result.summary(CWD.toString()));
    }

    @Test
    @DisplayName("取消回调只发信号：终止链由等待循环接手，回调本身不做树递归")
    void run_should_letCancelCallbackFireDestroy() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        TestCancellationToken token = new TestCancellationToken();
        new Thread(() -> {
            sleep(120L);
            token.cancel();
        }).start();

        new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(30_000L, 0L), sink, token);

        assertTrue(process.destroys() >= 1, "取消回调必须先发一个温和终止信号");
    }

    @Test
    @DisplayName("命令起不来是异常，而不是一个「失败的运行结果」")
    void run_should_throw_whenCommandCannotStart() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.FakeLauncher launcher = new ShellTestSupport.FakeLauncher(process);
        launcher.failWith(new IOException("解释器不存在"));

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> new ShellProcessRunner(launcher).run(invocation(5_000L, 0L),
                        new ShellTestSupport.RecordingSink(), CancellationToken.NONE));

        assertTrue(failure.getMessage().contains("解释器不存在"), failure.getMessage());
    }

    @Test
    @DisplayName("二进制输出不进正文，但字节数照旧上报")
    void run_should_reportBinaryOutput() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellTestSupport.RecordingSink sink = new ShellTestSupport.RecordingSink();
        byte[] binary = new byte[512];
        binary[10] = 0;
        process.writeBytes(binary);
        process.exitNow(0);

        ShellResult result = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(5_000L, 0L), sink, CancellationToken.NONE);

        assertEquals("", sink.body());
        assertTrue(result.summary(CWD.toString()).contains("512 字节已省略"), result.summary(CWD.toString()));
    }

    @Test
    @DisplayName("stdin 在启动后立即关闭——交互式命令必须快速失败而不是抢终端")
    void run_should_closeStdin() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.exitNow(0);

        new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(5_000L, 0L), new ShellTestSupport.RecordingSink(), CancellationToken.NONE);

        assertTrue(process.isStdinClosed());
    }

    @Test
    @DisplayName("收尾前先等输出泵结束——不等会丢掉输出的最后一截")
    void run_should_waitForOutputBeforeReturning() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.exitNow(0);

        new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(5_000L, 0L), new ShellTestSupport.RecordingSink(), CancellationToken.NONE);

        assertTrue(process.isOutputAwaited());
    }

    @Test
    @DisplayName("killAll 终止在途命令——插件停止时留着它们会变成孤儿进程")
    void killAll_should_terminateRunningProcesses() throws InterruptedException {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        ShellProcessRunner runner = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process));
        Thread running = new Thread(() -> runner.run(invocation(30_000L, 0L),
                new ShellTestSupport.RecordingSink(), CancellationToken.NONE));
        running.start();
        // 等它真的进到等待循环里，否则 killAll 时还没有在途进程
        sleep(200L);

        runner.killAll();
        running.join(2_000L);

        assertFalse(running.isAlive());
        assertFalse(process.isAlive());
    }

    @Test
    @DisplayName("不响应温和信号的进程会被强杀")
    void run_should_forceKill_whenProcessIgnoresTerm() {
        ShellTestSupport.FakeProcess process = new ShellTestSupport.FakeProcess();
        process.setRespondsToDestroy(false);

        ShellResult result = new ShellProcessRunner(new ShellTestSupport.FakeLauncher(process))
                .run(invocation(100L, 0L), new ShellTestSupport.RecordingSink(), CancellationToken.NONE);

        assertEquals(ShellResult.Termination.TIMEOUT, result.termination());
        assertEquals(1, process.forcedDestroys());
        assertEquals(1, process.destroys());
    }

    /**
     * 构造调用参数。
     *
     * @param timeoutMillis 墙钟超时
     * @param idleMillis    静默超时
     * @return 调用参数
     */
    private static ShellInvocation invocation(long timeoutMillis, long idleMillis) {
        return new ShellInvocation("echo hi", CWD, timeoutMillis, idleMillis, Collections.<String, String>emptyMap());
    }

    /**
     * 睡眠指定毫秒数，忽略中断。
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
     * 可手动取消的令牌，用于驱动取消路径。
     * <p>
     * 不 mock 内核的 {@code ReActTurnImpl}：这里要验证的是「令牌被取消后执行器会做什么」，
     * 而一个四行的实现比 mock 的桩更清楚。
     *
     * @author zcd
     */
    private static final class TestCancellationToken implements CancellationToken {

        /** 是否已取消。 */
        private volatile boolean cancelled;

        /** 取消回调。 */
        private volatile Runnable callback;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onCancel(Runnable action) {
            callback = action;
            if (cancelled && action != null) {
                action.run();
            }
        }

        /**
         * 触发取消。
         */
        void cancel() {
            cancelled = true;
            Runnable action = callback;
            if (action != null) {
                action.run();
            }
        }
    }

}
