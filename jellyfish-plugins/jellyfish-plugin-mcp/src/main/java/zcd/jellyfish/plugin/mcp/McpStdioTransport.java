package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MCP 的 stdio 传输：一个子进程 + 换行分隔的 JSON-RPC 分帧。
 * <p>
 * <b>分帧就是「一行一条消息」</b>：协议明确要求消息内部不得出现换行（序列化器写单行），
 * 因此按行读就是完整的分帧实现，不需要长度头，也不需要自己维护缓冲区。
 * <p>
 * <b>stderr 必须单独排空</b>：MCP server 的日志走 stderr，把它并进 stdout 会让 JSON 流里混进
 * 「连不上数据库」这种人类文本，表现为「偶尔收到一帧解析不了」。因此这里不合并两条流，
 * 而是另起一条守护线程持续读 stderr——不读的话，日志写满管道缓冲区会把 server 卡死。
 * <p>
 * <b>关停是四步而不是一步</b>：关闭 stdin（告诉 server 可以收尾）→ 杀直接子进程 →
 * 尽力杀进程树 → 必要强杀。少了「杀进程树」这一步，{@code npx} 拉起的 node 会继续跑，
 * 而现场表现是「插件已经停了，那个 server 还占着端口」。
 * <p>
 * 线程安全：{@link #send(String)} 同步写，{@link #readLine()} 由单条读线程调用。
 *
 * @author zcd
 */
final class McpStdioTransport implements McpTransport {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpStdioTransport.class);

    /** 优雅终止的宽限时间（毫秒）。 */
    private static final long GRACE_MILLIS = 2000L;

    /** 子进程。 */
    private final Process process;

    /** 标准输入写入器。 */
    private final BufferedWriter writer;

    /** 标准输出读取器。 */
    private final BufferedReader reader;

    /** stderr 排空线程。 */
    private final Thread stderrDrainer;

    /** 服务标识，仅用于日志。 */
    private final String serverId;

    /**
     * 构造传输。
     *
     * @param serverId      服务标识
     * @param process       子进程
     * @param writer        标准输入写入器
     * @param reader        标准输出读取器
     * @param stderrDrainer stderr 排空线程
     */
    private McpStdioTransport(String serverId, Process process, BufferedWriter writer,
                             BufferedReader reader, Thread stderrDrainer) {
        this.serverId = serverId;
        this.process = process;
        this.writer = writer;
        this.reader = reader;
        this.stderrDrainer = stderrDrainer;
    }

    /**
     * 启动一个 MCP server 子进程。
     * <p>
     * <b>环境是「继承 + 叠加」</b>：MCP server 常常需要 PATH 与用户的 API key，
     * 因此子进程继承父进程环境，配置里的 {@code env} 只做叠加。这与 shell 插件的
     * 「默认脱敏」是相反的取舍——那里是模型自己拼出来的命令，这里是用户在配置里写死的进程，
     * 而它拿不到所需的凭证就等于用不了。
     *
     * @param config 服务配置，不可为 {@code null}
     * @return 传输
     * @throws IOException 子进程启动失败时抛出
     */
    static McpStdioTransport start(McpServerConfig config) throws IOException {
        List<String> commandLine = new ArrayList<String>();
        commandLine.add(config.command());
        commandLine.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        // 刻意不合并两条流：stderr 里是服务端日志，混进 stdout 会破坏分帧
        builder.redirectErrorStream(false);
        builder.environment().putAll(config.env());
        Process process = builder.start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread drainer = drainStderr(config.id(), process);
        LOG.info("MCP server 进程已启动: id={} command={} args={}", config.id(), config.command(),
                config.args());
        return new McpStdioTransport(config.id(), process, writer, reader, drainer);
    }

    /**
     * 写一条消息。
     *
     * @param json 单行 JSON 文本，不可为 {@code null}
     * @throws IOException 写入失败（通常是子进程已退出）时抛出
     */
    @Override
    public void send(String json) throws IOException {
        synchronized (writer) {
            writer.write(json);
            writer.write('\n');
            writer.flush();
        }
    }

    /**
     * 阻塞读取一条消息。
     *
     * @return 一条消息文本；流结束（子进程退出）时返回 {@code null}
     * @throws IOException 读取失败时抛出
     */
    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    /**
     * 判断子进程是否仍在运行。
     *
     * @return 仍在运行返回 {@code true}
     */
    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            // 先关 stdin：多数 server 看到 EOF 会自行收尾，这是代价最小的一种结束方式
            synchronized (writer) {
                writer.close();
            }
        } catch (IOException e) {
            LOG.debug("关闭 MCP server stdin 失败（通常无害）: id={}", serverId, e);
        }
        if (process.isAlive()) {
            process.destroy();
            boolean exited = awaitExit();
            if (!exited) {
                killDescendants(process);
                process.destroyForcibly();
                awaitExit();
            }
        }
        joinQuietly(stderrDrainer);
        LOG.info("MCP server 进程已结束: id={} exitCode={}", serverId, exitCode());
    }

    /**
     * 等待子进程退出。
     *
     * @return 已退出返回 {@code true}
     */
    private boolean awaitExit() {
        try {
            return process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /**
     * 取退出码。
     *
     * @return 退出码；尚未退出时返回 {@code -1}
     */
    private int exitCode() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /**
     * 尽力终止子进程的整棵进程树。
     * <p>
     * <b>为什么需要它</b>：最常见的 MCP server 用法是 {@code npx -y <package>}，
     * 真正干活的是 npx 拉起的 node 孙进程。只杀直接子进程，表现是「插件已经停了，
     * server 还占着端口与文件句柄」。
     * <p>
     * <b>为什么是「尽力」</b>：JDK 8 没有 {@code ProcessHandle.descendants()}，
     * 只能靠 {@code pgrep -P} 递归；它不存在或没权限时就只能杀到直接子进程为止。
     * 这是已知边界，日志里有痕迹。
     *
     * @param process 子进程
     */
    private static void killDescendants(Process process) {
        try {
            long pid = pidOf(process);
            if (pid <= 0) {
                LOG.debug("取不到子进程 PID，进程树只能杀到直接子进程");
                return;
            }
            for (String child : childrenOf(pid)) {
                signal("TERM", child);
            }
        } catch (RuntimeException e) {
            LOG.debug("终止 MCP server 进程树失败（尽力而为）", e);
        }
    }

    /**
     * 取进程 PID。
     * <p>
     * <b>为什么不是一句 {@code process.pid()}</b>：它是 Java 9 才有的方法，而本项目跑在 Java 8 上。
     * 因此先反射试 {@code pid()}（新 JDK 走这条），失败再退到 JDK 8 的 {@code UNIXProcess}
     * 打印格式。两条都失败就放弃进程树——那是已知边界，而不是出错。
     *
     * @param process 子进程
     * @return PID；取不到时返回 {@code -1}
     */
    private static long pidOf(Process process) {
        try {
            return ((Number) Process.class.getMethod("pid").invoke(process)).longValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("Process.pid() 不可用，改用 toString 解析", e);
        }
        String text = String.valueOf(process);
        int start = text.indexOf("pid=");
        if (start < 0) {
            return -1L;
        }
        int end = start + "pid=".length();
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return parsePid(text.substring(start + "pid=".length(), end));
    }

    /**
     * 列出某个进程的直接子进程 PID。
     *
     * @param pid 父进程 PID
     * @return 子进程 PID 文本列表；{@code pgrep} 不可用时返回空列表
     */
    private static List<String> childrenOf(long pid) {
        List<String> children = new ArrayList<String>();
        if (pid <= 0) {
            return children;
        }
        Process pgrep = null;
        try {
            pgrep = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = output.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    children.add(trimmed);
                }
            }
            pgrep.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.debug("pgrep 不可用，进程树只能杀到直接子进程", e);
        } finally {
            if (pgrep != null) {
                pgrep.destroy();
            }
        }
        // 先递归再信号：拿到的是「父在前」的列表，从最深处开始杀更接近终态
        List<String> deepFirst = new ArrayList<String>();
        for (String child : children) {
            deepFirst.addAll(childrenOf(parsePid(child)));
        }
        deepFirst.addAll(children);
        return deepFirst;
    }

    /**
     * 解析 PID 文本。
     *
     * @param text PID 文本
     * @return 数值；无法解析时返回 {@code -1}（{@code pgrep -P} 对它会返回空）
     */
    private static long parsePid(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * 给一个 PID 发信号。
     *
     * @param signal 信号名（{@code TERM} / {@code KILL}）
     * @param pid    PID 文本
     */
    private static void signal(String signal, String pid) {
        try {
            new ProcessBuilder("kill", "-" + signal, pid).start().waitFor(GRACE_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.debug("kill -{} {} 失败（进程可能已经退出）", signal, pid, e);
        }
    }

    /**
     * 起一条守护线程排空 stderr。
     *
     * @param serverId 服务标识，用于日志
     * @param process  子进程
     * @return 排空线程
     */
    private static Thread drainStderr(String serverId, Process process) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stderr.readLine()) != null) {
                    LOG.debug("[mcp:{}] {}", serverId, line);
                }
            } catch (IOException e) {
                LOG.debug("读取 MCP server 的 stderr 结束: id={}", serverId, e);
            }
        }, "mcp-stderr-" + serverId);
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    /**
     * 安静地等待一条线程结束。
     *
     * @param thread 线程，可为 {@code null}
     */
    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
