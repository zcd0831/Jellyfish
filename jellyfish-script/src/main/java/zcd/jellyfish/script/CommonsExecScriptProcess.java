package zcd.jellyfish.script;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteStreamHandler;
import org.apache.commons.exec.ShutdownHookProcessDestroyer;
import org.apache.commons.exec.StreamPumper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 用操作系统命令启动的脚本进程（Apache Commons Exec 实现）。
 * <p>
 * <b>为什么用 Commons Exec 启动却自己泵流</b>：这一版的 {@code PumpStreamHandler} 有两个与
 * 「长期存活的交互式子进程」相冲突的行为——{@code setProcessInputStream(null)} 会<b>直接关掉子进程的 stdin</b>
 * （于是脚本一启动就看到 EOF、立刻退出），而它自带的 stdin 泵只服务于「一次写完就结束」的用法。
 * 本方案要的是「长期在线、逐行收发」，因此这里只借用 Commons Exec 的进程启动与 JVM 退出兜底，
 * 泵流改用它的 {@link StreamPumper}（同一个库的公开类）加一个只做枚举的空流处理器，
 * stdin 则直接写子进程。
 * <p>
 * <b>为什么不用管道对转接</b>：JDK 的 {@code PipedInputStream} 在写入侧不唤醒读者，
 * 读侧按 1 秒粒度超时重试，每一跳都可能白等一秒。用它转接 stdout，一次请求应答就会莫名慢上几秒，
 * 现象是「调用偶尔超时」——排查时几乎不会有人想到是管道实现的问题。
 * <p>
 * <b>不设 {@code ExecuteWatchdog}</b>：它按墙上时钟到点就 {@code destroy()}，而网关是设计成
 * 可以空闲十分钟再退场的长命进程，给它装一个硬超时等于定时杀掉正常的运行时。
 * 真正需要超时的是「某一次调用」，那由 {@code ScriptRpc} 与网关侧的 {@code kill_worker} 负责。
 * <p>
 * <b>stderr 一律进日志、不参与协议</b>：脚本与网关的调试输出、解释器警告、未捕获异常的 traceback
 * 都在 stderr 上。这是「脚本能打印调试信息」与「stdout 只有协议」两条要求能同时成立的唯一办法。
 * <p>
 * <b>僵尸与孤儿由三层兜住</b>：{@link ShutdownHookProcessDestroyer} 保证 JVM 退出时子进程收到 TERM；
 * {@link #close(long, long)} 保证正常关闭会等到子进程真的没了；脚本侧还依赖「父进程死了就自杀」
 * 兜住 {@code kill -9} 这种谁都收不到通知的场景。
 * <p>
 * 线程安全：{@link #send(String)} 由多个调用线程并发进入，因此整行写入必须原子。
 *
 * @author zcd
 */
final class CommonsExecScriptProcess implements ScriptProcess {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CommonsExecScriptProcess.class);

    /** 等待子进程真正被操作系统创建出来的毫秒数。 */
    private static final long START_TIMEOUT_MILLIS = 10_000L;

    /** 启动命令。 */
    private final List<String> command;

    /** 子进程环境变量（完整替换）。 */
    private final Map<String, String> environment;

    /** 子进程工作目录。 */
    private final Path directory;

    /** 协议行接收方。 */
    private final Consumer<String> lines;

    /** 退出回调。 */
    private final Consumer<Integer> onExit;

    /** 保护整行写入。 */
    private final Object writeLock = new Object();

    /** 是否已判定为不可用（进程已退出或关闭中）。 */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** 子进程是否已退出。 */
    private final CountDownLatch exited = new CountDownLatch(1);

    /** 执行器，用于取回 {@link Process} 句柄。 */
    private final CapturingExecutor executor = new CapturingExecutor();

    /** 协议行汇聚流。 */
    private final LineOutputStream protocolSink;

    /** stderr 日志汇聚流。 */
    private final LineOutputStream stderrSink =
            new LineOutputStream(line -> LOG.info("[script] {}", line));

    /** 子进程句柄；启动完成后可见。 */
    private volatile Process process;

    /**
     * 构造并立即启动进程。
     *
     * @param command     命令与参数，不可为 {@code null} 且非空
     * @param environment 子进程环境变量，不可为 {@code null}
     * @param directory   工作目录，可为 {@code null}
     * @param lines       协议行接收方，不可为 {@code null}
     * @param onExit      退出回调，不可为 {@code null}
     * @throws JellyfishException 进程无法启动时抛出
     */
    CommonsExecScriptProcess(List<String> command, Map<String, String> environment, Path directory,
                             Consumer<String> lines, Consumer<Integer> onExit) {
        this.command = command;
        this.environment = environment;
        this.directory = directory;
        this.lines = lines;
        this.onExit = onExit;
        // 在构造器里建而不是用字段初始化：字段初始化执行时 lines 还没赋值
        this.protocolSink = new LineOutputStream(this.lines::accept);
        start();
    }

    @Override
    public void send(String line) {
        Process current = process;
        if (stopped.get() || current == null) {
            throw new JellyfishException("脚本进程已不可用，无法发送协议帧: " + describeCommand());
        }
        byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                OutputStream stdin = current.getOutputStream();
                stdin.write(payload);
                stdin.flush();
            } catch (IOException e) {
                stopped.set(true);
                throw new JellyfishException("写入脚本进程失败（进程可能已退出）: " + describeCommand(), e);
            }
        }
    }

    @Override
    public boolean isAlive() {
        Process current = process;
        return !stopped.get() && current != null && current.isAlive();
    }

    @Override
    public void close(long graceMillis, long killMillis) {
        Process current = process;
        if (current == null) {
            stopped.set(true);
            return;
        }
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // 先关 stdin：解释器读到 EOF 就走正常退出路径，比信号温和，
        // 也让「网关正在收信号、worker 来不及回收」的概率最低
        closeQuietly(current.getOutputStream());
        if (!awaitExit(graceMillis)) {
            LOG.warn("脚本进程未在 {} ms 内退出，发送终止信号: {}", Long.valueOf(graceMillis), describeCommand());
            current.destroy();
        }
        if (!awaitExit(graceMillis)) {
            LOG.warn("脚本进程未响应终止信号，强杀: {}", describeCommand());
            current.destroyForcibly();
            if (!awaitExit(killMillis)) {
                LOG.error("脚本进程强杀后仍未退出，可能出现残留进程: {}", describeCommand());
            }
        }
    }

    /**
     * 启动进程并接线。
     *
     * @throws JellyfishException 启动失败或超时时抛出
     */
    private void start() {
        // 退出码不做「非零即异常」的判定：网关非零退出的原因很多，本类只用它做日志与回调；
        // 让 Commons Exec 抛 ExecuteException 只会把真正的退出码埋进异常里更难取
        executor.setExitValues(null);
        // 空流处理器：泵流由本类自己做，理由见类注释
        executor.setStreamHandler(NoopStreamHandler.INSTANCE);
        executor.setProcessDestroyer(new ShutdownHookProcessDestroyer());
        if (directory != null) {
            executor.setWorkingDirectory(directory.toFile());
        }
        CommandLine parsed = new CommandLine(command.get(0));
        for (int index = 1; index < command.size(); index++) {
            parsed.addArgument(command.get(index));
        }
        Thread runner = new Thread(() -> run(parsed), "jellyfish-script-exec");
        runner.setDaemon(true);
        runner.start();
        captureProcess();
    }

    /**
     * 取回子进程句柄并启动泵流线程。
     * <p>
     * 必须在启动期同步确认句柄存在，否则「解释器不存在」会以「进程句柄为 null」的形态
     * 一路漂到第一次 {@code send}，报错现场与原因相隔好几个栈帧。
     *
     * @throws JellyfishException 启动失败或超时时抛出
     */
    private void captureProcess() {
        if (!executor.awaitLaunch(START_TIMEOUT_MILLIS)) {
            throw new JellyfishException("脚本进程启动超时（" + START_TIMEOUT_MILLIS + " ms）: "
                    + describeCommand());
        }
        Process launched = executor.launched();
        if (launched == null) {
            throw new JellyfishException("脚本进程启动失败: " + describeCommand(), executor.launchFailure());
        }
        this.process = launched;
        pump("jellyfish-script-stdout", launched.getInputStream(), protocolSink);
        pump("jellyfish-script-stderr", launched.getErrorStream(), stderrSink);
    }

    /**
     * 启动一个泵流线程。
     * <p>
     * 用 Commons Exec 自己的 {@link StreamPumper}，并让它在中止时关闭目标流：
     * 关闭会补发没有换行结尾的末行，而进程被杀时最后一行往往正是最关键的一句。
     *
     * @param name   线程名
     * @param source 子进程的输出流
     * @param sink   行汇聚流
     */
    private static void pump(String name, InputStream source, OutputStream sink) {
        Thread thread = new Thread(new StreamPumper(source, sink, true), name);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 执行进程并等待退出，运行在独立的守护线程上。
     * <p>
     * Commons Exec 的 {@code execute} 会阻塞到进程退出，因此它必须离开调用线程——
     * 否则 {@code start()} 就不再是「启动」而是「同步执行」。
     *
     * @param parsed 命令行
     */
    private void run(CommandLine parsed) {
        int code;
        try {
            code = executor.execute(parsed, environment);
        } catch (IOException e) {
            LOG.error("脚本进程执行失败: {}", describeCommand(), e);
            code = -1;
        } finally {
            stopped.set(true);
            exited.countDown();
        }
        if (executor.launched() == null) {
            // 启动失败已经由构造期同步上报，这里再回调一次只会让上层重复处理同一个故障
            LOG.debug("脚本进程未成功启动，跳过退出回调: {}", describeCommand());
            return;
        }
        LOG.info("脚本进程已退出: exitCode={} {}", Integer.valueOf(code), describeCommand());
        try {
            onExit.accept(Integer.valueOf(code));
        } catch (RuntimeException e) {
            LOG.warn("脚本进程退出回调失败: {}", e.toString());
        }
    }

    /**
     * 等待进程退出。
     *
     * @param millis 等待毫秒数
     * @return 已退出返回 {@code true}
     */
    private boolean awaitExit(long millis) {
        try {
            return exited.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
            LOG.debug("关闭脚本进程流失败: {}", e.getMessage());
        }
    }

    /**
     * 描述启动命令，用于日志与报错。
     *
     * @return 命令文本
     */
    private String describeCommand() {
        return String.join(" ", command);
    }

    /**
     * 只做枚举的空流处理器。
     * <p>
     * Commons Exec 的执行流程要求有一个流处理器（否则内部会空指针），而本类已经自己接管泵流，
     * 因此这里什么都不做。真正的管道故障由泵流线程与写入路径各自暴露。
     */
    private static final class NoopStreamHandler implements ExecuteStreamHandler {

        /** 唯一实例，无状态。 */
        private static final NoopStreamHandler INSTANCE = new NoopStreamHandler();

        @Override
        public void setProcessInputStream(OutputStream os) {
            // 有意为空：stdin 由 send 直接写子进程，交给处理器反而会被立刻关掉
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
     * 能取回 {@link Process} 句柄的执行器。
     * <p>
     * Commons Exec 不对外暴露子进程句柄，而两段式关闭需要它：{@code destroy()} 与
     * {@code destroyForcibly()} 都只能从 {@code Process} 上调用，泵流也需要它拿输出流。
     * {@code DefaultExecutor.launch} 是 {@code protected}，因此重写它是官方留的扩展点，而不是反射或猜测。
     */
    private static final class CapturingExecutor extends DefaultExecutor {

        /** 启动是否已结束（成功或失败）。 */
        private final CountDownLatch launchFinished = new CountDownLatch(1);

        /** 子进程句柄；启动失败时为 {@code null}。 */
        private volatile Process launched;

        /** 启动失败原因；成功时为 {@code null}。 */
        private volatile IOException failure;

        @Override
        protected Process launch(CommandLine command, Map<String, String> env, File dir) throws IOException {
            try {
                Process child = super.launch(command, env, dir);
                this.launched = child;
                return child;
            } catch (IOException e) {
                this.failure = e;
                throw e;
            } finally {
                launchFinished.countDown();
            }
        }

        /**
         * 等待启动结束。
         *
         * @param millis 等待毫秒数
         * @return 启动已结束返回 {@code true}
         */
        private boolean awaitLaunch(long millis) {
            try {
                return launchFinished.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * 获取子进程句柄。
         *
         * @return 子进程句柄；启动失败或尚未启动时为 {@code null}
         */
        private Process launched() {
            return launched;
        }

        /**
         * 获取启动失败原因。
         *
         * @return 失败原因；成功时为 {@code null}
         */
        private IOException launchFailure() {
            return failure;
        }
    }

    /**
     * 把字节流按行切出来交给接收方的输出流。
     * <p>
     * <b>按字节累积、成行时才解码</b>：{@code OutputStream.write(int)} 给的是字节而不是字符，
     * 逐字节转字符会把非 ASCII 的 UTF-8 文本撕成乱码——脚本里的中文日志恰恰是最需要看清的部分；
     * 而按块解码又可能在多字节字符中间断开，因此必须累积到换行再整体解码。
     * <b>缓冲区有上限</b>：一个从不换行的输出（例如二进制垃圾）不能让本类无限吃内存，
     * 超限就当一行截断输出。
     * <p>
     * 线程安全：写入来自泵流线程，收尾可能来自执行线程。
     */
    static final class LineOutputStream extends OutputStream {

        /** 单行最大字节数，超过即截断输出。 */
        private static final int MAX_LINE_BYTES = 64 * 1024;

        /** 成行后的接收方。 */
        private final Consumer<String> sink;

        /** 累积未成行的字节。 */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /** 是否已关闭。 */
        private boolean closed;

        /**
         * 构造行汇聚流。
         *
         * @param sink 成行后的接收方，不可为 {@code null}
         */
        LineOutputStream(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void write(int value) {
            if (closed) {
                return;
            }
            if (value == '\n') {
                emit();
                return;
            }
            if (value == '\r') {
                return;
            }
            buffer.write(value);
            if (buffer.size() >= MAX_LINE_BYTES) {
                emit();
            }
        }

        @Override
        public synchronized void close() {
            emit();
            closed = true;
        }

        /**
         * 输出缓冲区内容并清空。
         * <p>
         * 空行直接丢弃：它在协议层与日志里都没有信息量，留着只会让日志出现成片空白。
         */
        private void emit() {
            if (buffer.size() == 0) {
                return;
            }
            String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            sink.accept(line);
        }
    }
}
