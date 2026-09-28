package zcd.jellyfish.plugin.shell;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.StreamPumper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 用 Apache Commons Exec 启动命令的默认实现。
 * <p>
 * <b>为什么自己泵流而不是用 {@code PumpStreamHandler}</b>：那个处理器在设置进程时会
 * <b>直接关掉子进程的 stdin</b>（对它是「一次性写完就结束」的语义），并且它自己的线程无法被
 * 有界地等待。我们需要的是「合并两条流 + 收尾时能等它们结束」，因此泵流改用它同一个库里的
 * {@link StreamPumper}，收尾由 {@link ShellProcessRunner} 控制。
 * <p>
 * <b>合并而不是分节</b>：stdout 与 stderr 写进同一个流，顺序即到达顺序，像终端一样。
 * 分节显示需要引入 channel 概念，而收益只是「看起来整齐一点」——命令失败的原因常常恰好是
 * stderr 与 stdout 交错的顺序。
 * <p>
 * <b>Windows 未验证</b>：非 POSIX 平台映射成 {@code cmd.exe /c}，但它没有在真机上跑过，
 * 因此不要把它当成已支持。
 *
 * @author zcd
 */
final class CommonsExecShellProcessLauncher implements ShellProcessLauncher {

    /** 输出泵线程名前缀。 */
    private static final String PUMP_PREFIX = "jellyfish-shell-";

    @Override
    public ShellProcess launch(ShellInvocation invocation, OutputStream mergedOutput) throws IOException {
        // 非零退出码不是异常：grep 没匹配到返回 1 是信息而不是故障，
        // 让 Commons Exec 抛 ExecuteException 只会把退出码埋进异常里更难取
        CapturingExecutor executor = new CapturingExecutor();
        executor.setExitValues(null);
        executor.setStreamHandler(NoopStreamHandler.INSTANCE);
        Process process = executor.launch(shellCommand(invocation.command()), invocation.environment(),
                invocation.workingDirectory().toFile());
        Thread stdout = pump(PUMP_PREFIX + "stdout", process.getInputStream(), mergedOutput);
        Thread stderr = pump(PUMP_PREFIX + "stderr", process.getErrorStream(), mergedOutput);
        return new CommonsExecShellProcess(process, stdout, stderr);
    }

    /**
     * 构造「用系统 shell 执行这条命令行原文」的命令。
     *
     * @param command 命令原文
     * @return 命令行
     */
    private static CommandLine shellCommand(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        CommandLine line = new CommandLine(windows ? "cmd.exe" : "/bin/sh");
        line.addArgument(windows ? "/c" : "-c");
        // 不做引号处理：命令原文必须作为一个 argv 元素原样交给 shell，
        // 任何转义都会改变用户写的重定向、管道与通配符的含义
        line.addArgument(command, false);
        return line;
    }

    /**
     * 启动一条输出泵线程。
     * <p>
     * <b>不让泵关闭目标流</b>：两条泵写的是同一个流，先结束的那条一关，另一条之后的内容就丢了。
     * 收尾由 {@link ShellProcessRunner} 在两条都结束后统一做。
     *
     * @param name   线程名
     * @param source 子进程输出流
     * @param sink   合并输出目标
     * @return 线程
     */
    private static Thread pump(String name, InputStream source, OutputStream sink) {
        Thread thread = new Thread(new StreamPumper(source, sink, false), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Commons Exec 启动出来的进程。
     *
     * @author zcd
     */
    private static final class CommonsExecShellProcess implements ShellProcess {

        /** 底层进程。 */
        private final Process process;

        /** stdout 泵线程。 */
        private final Thread stdout;

        /** stderr 泵线程。 */
        private final Thread stderr;

        /** 进程标识，取不到时为 {@code -1}。 */
        private final long pid;

        /**
         * 构造进程句柄。
         *
         * @param process 底层进程
         * @param stdout  stdout 泵线程
         * @param stderr  stderr 泵线程
         */
        CommonsExecShellProcess(Process process, Thread stdout, Thread stderr) {
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
            this.pid = ProcessTrees.pidOf(process);
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public boolean waitFor(long millis) throws InterruptedException {
            return process.waitFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        public int exitValue() {
            return process.exitValue();
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public void destroy() {
            process.destroy();
        }

        @Override
        public void destroyForcibly() {
            process.destroyForcibly();
        }

        @Override
        public void closeStdin() {
            closeQuietly(process.getOutputStream());
        }

        @Override
        public void awaitOutput(long millis) {
            join(stdout, millis);
            join(stderr, millis);
        }

        /**
         * 有界等待一条泵线程结束。
         *
         * @param thread 线程，可为 {@code null}
         * @param millis 等待毫秒数
         */
        private static void join(Thread thread, long millis) {
            if (thread == null) {
                return;
            }
            try {
                thread.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 静默关闭流。
         *
         * @param stream 待关闭的流，可为 {@code null}
         */
        private static void closeQuietly(Closeable stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException e) {
                // 关掉一个已经断掉的 stdin 是正常情况，不值得记日志
            }
        }
    }

    /**
     * 什么都不做的流处理器。
     * <p>
     * Commons Exec 的启动流程要求有一个流处理器（否则内部会空指针），而本类已经自己接管泵流，
     * 因此这里只做枚举。真正的管道故障由泵流线程暴露。
     *
     * @author zcd
     */
    private static final class NoopStreamHandler implements ExecuteStreamHandler {

        /** 唯一实例，无状态。 */
        private static final NoopStreamHandler INSTANCE = new NoopStreamHandler();

        @Override
        public void setProcessInputStream(OutputStream os) {
            // 有意为空：stdin 由 ShellProcess.closeStdin 关闭，交给处理器反而不受我们控制
        }

        @Override
        public void setProcessErrorStream(InputStream is) {
            // 有意为空：stderr 由泵流线程接管
        }

        @Override
        public void setProcessOutputStream(InputStream is) {
            // 有意为空：stdout 由泵流线程接管
        }

        @Override
        public void start() {
            // 有意为空：泵流线程在取到进程句柄后自行启动
        }

        @Override
        public void stop() {
            // 有意为空：泵流随管道 EOF 自然结束
        }
    }

    /**
     * 能把 {@link Process} 句柄交出来的执行器。
     * <p>
     * Commons Exec 不对外暴露子进程句柄，而终止链需要它（{@code destroy()} 与
     * {@code destroyForcibly()} 只能从 {@code Process} 上调用）。{@code DefaultExecutor.launch}
     * 是 {@code protected}，重写它是官方留的扩展点，而不是反射或猜测。
     *
     * @author zcd
     */
    private static final class CapturingExecutor extends DefaultExecutor {

        /**
         * 启动进程并交出句柄。
         *
         * @param command 命令行
         * @param env     环境变量
         * @param dir     工作目录
         * @return 子进程句柄
         * @throws IOException 启动失败时抛出
         */
        @Override
        public Process launch(CommandLine command, Map<String, String> env, File dir) throws IOException {
            return super.launch(command, env, dir);
        }
    }
}
