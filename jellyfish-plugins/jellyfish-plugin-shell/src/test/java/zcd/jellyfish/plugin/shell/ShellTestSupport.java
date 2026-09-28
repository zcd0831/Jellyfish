package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.extension.ToolOutputSink;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件单测的公共假件：一个可驱动状态的假进程与一个假启动器。
 * <p>
 * <b>为什么不 fork 真实进程</b>：超时、静默与取消这三条路径要靠「进程一直不退出」来验证，
 * 而真实进程要做到这一点就得真的 sleep——慢、依赖平台，还可能在 CI 上留下残留进程。
 * 假件让这几种状态变成几行 setup。
 *
 * @author zcd
 */
final class ShellTestSupport {

    /**
     * 工具类，禁止实例化。
     */
    private ShellTestSupport() {
    }

    /**
     * 记录型捕获通道：把写入与元数据行分别记下来，供断言使用。
     * <p>
     * 刻意手写而不是 mock：断言的对象是「收到了哪几段文本」，
     * 记录器读起来比 {@code verify} 更直观，也便于同时检查拼接结果。
     *
     * @author zcd
     */
    static final class RecordingSink implements ToolOutputSink {

        /** 正文。 */
        private final StringBuilder body = new StringBuilder();

        /** 元数据。 */
        private final StringBuilder summary = new StringBuilder();

        /** 收尾结果，{@code null} 表示尚未收尾。 */
        private String finished;

        @Override
        public void write(String chunk) {
            if (finished != null) {
                throw new IllegalStateException("收尾后不应再写入");
            }
            body.append(chunk);
        }

        @Override
        public void summary(String text) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(text);
        }

        @Override
        public String finish() {
            if (finished == null) {
                finished = summary.length() == 0 ? body.toString() : summary + "\n" + body;
            }
            return finished.isEmpty() ? null : finished;
        }

        /**
         * 获取正文。
         *
         * @return 正文
         */
        String body() {
            return body.toString();
        }

        /**
         * 获取元数据行。
         *
         * @return 元数据行
         */
        String summaryText() {
            return summary.toString();
        }
    }

    /**
     * 假启动器：把指定的假进程交给执行器。
     *
     * @author zcd
     */
    static final class FakeLauncher implements ShellProcessLauncher {

        /** 要交出去的进程。 */
        private final FakeProcess process;

        /** 记录每一次收到的调用参数。 */
        private final List<ShellInvocation> invocations = new ArrayList<ShellInvocation>();

        /** 让 {@link #launch} 抛出的异常，可为 {@code null}。 */
        private IOException failure;

        /**
         * 构造假启动器。
         *
         * @param process 假进程，不可为 {@code null}
         */
        FakeLauncher(FakeProcess process) {
            this.process = process;
        }

        @Override
        public ShellProcess launch(ShellInvocation invocation, OutputStream mergedOutput) throws IOException {
            invocations.add(invocation);
            if (failure != null) {
                throw failure;
            }
            process.attach(mergedOutput);
            return process;
        }

        /**
         * 让启动失败。
         *
         * @param cause 失败原因
         */
        void failWith(IOException cause) {
            this.failure = cause;
        }

        /**
         * 获取收到的调用参数。
         *
         * @return 调用参数列表
         */
        List<ShellInvocation> invocations() {
            return invocations;
        }
    }

    /**
     * 假进程：由测试决定它什么时候退出、输出什么、以及是否响应终止信号。
     *
     * @author zcd
     */
    static final class FakeProcess implements ShellProcess {

        /** 输出目标。 */
        private volatile OutputStream output;

        /** 是否已退出。 */
        private final CountDownLatch exited = new CountDownLatch(1);

        /** 退出码。 */
        private volatile int exitCode;

        /** 收到的温和终止次数。 */
        private final AtomicInteger destroys = new AtomicInteger();

        /** 收到的强杀次数。 */
        private final AtomicInteger forcedDestroys = new AtomicInteger();

        /** stdin 是否已被关闭。 */
        private volatile boolean stdinClosed;

        /** 是否等到了输出泵结束。 */
        private volatile boolean outputAwaited;

        /** 是否响应终止信号：{@code false} 时只能靠强杀。 */
        private volatile boolean respondsToDestroy = true;

        /**
         * 接线之前写入的内容。
         * <p>
         * 测试往往想表达「命令一启动就打印了这些」，而那时输出目标还没被交进来，
         * 因此先缓一下、在接线时补发。
         */
        private final List<byte[]> buffered = new ArrayList<byte[]>();

        /**
         * 接线输出目标。
         *
         * @param stream 输出目标
         */
        void attach(OutputStream stream) {
            this.output = stream;
            for (byte[] bytes : buffered) {
                writeBytes(bytes);
            }
            buffered.clear();
        }

        /**
         * 立刻正常退出。
         *
         * @param code 退出码
         */
        void exitNow(int code) {
            this.exitCode = code;
            exited.countDown();
        }

        /**
         * 写出文本，模拟命令产生输出。
         *
         * @param text 文本
         */
        void write(String text) {
            writeBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /**
         * 写出原始字节，模拟命令产生非文本输出。
         *
         * @param bytes 字节
         */
        void writeBytes(byte[] bytes) {
            OutputStream stream = output;
            if (stream == null) {
                buffered.add(bytes);
                return;
            }
            try {
                stream.write(bytes);
                stream.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * 设置是否响应温和终止。
         *
         * @param responsive {@code false} 表示只能被强杀
         */
        void setRespondsToDestroy(boolean responsive) {
            this.respondsToDestroy = responsive;
        }

        /**
         * 获取收到的温和终止次数。
         *
         * @return 次数
         */
        int destroys() {
            return destroys.get();
        }

        /**
         * 获取收到的强杀次数。
         *
         * @return 次数
         */
        int forcedDestroys() {
            return forcedDestroys.get();
        }

        /**
         * 判断 stdin 是否已被关闭。
         *
         * @return 已关闭返回 {@code true}
         */
        boolean isStdinClosed() {
            return stdinClosed;
        }

        /**
         * 判断是否等过输出泵。
         *
         * @return 等过返回 {@code true}
         */
        boolean isOutputAwaited() {
            return outputAwaited;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }

        @Override
        public boolean waitFor(long millis) throws InterruptedException {
            return exited.await(millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public long pid() {
            // 刻意不存在的 PID：进程树查找要跑 pgrep，而单测不该依赖外部命令
            return -1L;
        }

        @Override
        public void destroy() {
            destroys.incrementAndGet();
            if (respondsToDestroy) {
                exitNow(143);
            }
        }

        @Override
        public void destroyForcibly() {
            forcedDestroys.incrementAndGet();
            exitNow(137);
        }

        @Override
        public void closeStdin() {
            stdinClosed = true;
        }

        @Override
        public void awaitOutput(long millis) {
            outputAwaited = true;
        }
    }
}
