package zcd.jellyfish.plugin.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 进程树的尽力终止。
 * <p>
 * <b>为什么需要它</b>：{@code sh -c} 的直接子进程是那个 shell，而真正干活的是它的孩子——
 * {@code npm run dev} 会再拉起 node，{@code make} 会拉起编译器。只杀直接子进程的后果是
 * 「工具报告已终止，端口却还占着、日志还在长」，而用户以为它停了。
 * <p>
 * <b>为什么是「尽力」</b>：JDK 8 没有 {@code ProcessHandle.descendants()}，只能靠外部命令
 * {@code pgrep -P} 递归查找，而它可能不存在（精简容器）、可能没有权限、也可能在我们查完之后
 * 又生出新的孩子。因此这里全程不抛异常：<b>杀不干净是已知边界</b>，不是 bug。
 * <p>
 * <b>取 PID 为什么要试三条路</b>：{@code Process.pid()} 是 Java 9 才有的方法；Java 8 上只能读
 * {@code UNIXProcess} 的私有字段；再不行就解析 {@code toString()} 里的 {@code pid=NNN}。
 * 三条都失败时退化为「只杀直接子进程」，并记一条 DEBUG——不把「拿不到 PID」升级成「终止失败」。
 *
 * @author zcd
 */
final class ProcessTrees {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ProcessTrees.class);

    /**
     * 工具类，禁止实例化。
     */
    private ProcessTrees() {
    }

    /**
     * 终止整棵进程树：先温和、再强杀。
     *
     * @param process      进程句柄，不可为 {@code null}
     * @param graceMillis  温和终止到强杀之间的宽限毫秒数
     * @param killMillis   强杀之后等待退出的毫秒数
     */
    static void killTree(ShellProcess process, long graceMillis, long killMillis) {
        List<Long> descendants = descendantsOf(process.pid());
        for (Long pid : descendants) {
            signal(pid, "TERM");
        }
        process.destroy();
        if (awaitExit(process, graceMillis)) {
            return;
        }
        LOG.warn("进程未响应终止信号，强杀: pid={}", Long.valueOf(process.pid()));
        for (Long pid : descendantsOf(process.pid())) {
            signal(pid, "KILL");
        }
        process.destroyForcibly();
        if (!awaitExit(process, killMillis)) {
            LOG.error("强杀后进程仍未退出，可能出现残留进程: pid={}", Long.valueOf(process.pid()));
        }
    }

    /**
     * 递归查找某个进程的全部后代。
     *
     * @param pid 进程标识，小于等于 0 时直接返回空列表
     * @return 后代进程标识，保证非 {@code null}
     */
    static List<Long> descendantsOf(long pid) {
        List<Long> result = new ArrayList<Long>();
        if (pid <= 0) {
            return result;
        }
        collect(pid, result, new HashSet<Long>(), 0);
        return result;
    }

    /**
     * 递归收集后代。
     *
     * @param pid   父进程标识
     * @param out   结果列表
     * @param seen  已见过的标识，防止（理论上不可能的）环
     * @param depth 当前深度，用于兜住异常深的进程树
     */
    private static void collect(long pid, List<Long> out, Set<Long> seen, int depth) {
        if (depth > 16 || !seen.add(Long.valueOf(pid))) {
            return;
        }
        List<Long> children = childrenOf(pid);
        for (Long child : children) {
            out.add(child);
            collect(child.longValue(), out, seen, depth + 1);
        }
    }

    /**
     * 查直接子进程。
     *
     * @param pid 父进程标识
     * @return 子进程标识，保证非 {@code null}
     */
    private static List<Long> childrenOf(long pid) {
        List<Long> children = new ArrayList<Long>();
        try {
            Process pgrep = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addPid(children, line.trim());
                }
            }
            pgrep.waitFor();
        } catch (IOException e) {
            // pgrep 不存在（精简容器）或没有权限：退化为只杀直接子进程
            LOG.debug("查找子进程失败，跳过进程树终止: pid={} reason={}", Long.valueOf(pid), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return children;
    }

    /**
     * 解析并收集一行 pgrep 输出。
     *
     * @param out  结果列表
     * @param line 一行输出
     */
    private static void addPid(List<Long> out, String line) {
        if (line.isEmpty()) {
            return;
        }
        try {
            out.add(Long.valueOf(Long.parseLong(line)));
        } catch (NumberFormatException e) {
            LOG.debug("忽略无法解析的进程标识: {}", line);
        }
    }

    /**
     * 给某个进程发信号。
     *
     * @param pid    进程标识
     * @param signal 信号名（{@code TERM} / {@code KILL}）
     */
    private static void signal(long pid, String signal) {
        try {
            Process kill = new ProcessBuilder("kill", "-" + signal, String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            // 读空输出：kill 正常不打印任何东西，但管道写满会让它自己卡住
            // （不用 Redirect.DISCARD：那是 Java 9 才有的 API，本项目跑在 1.8 上）
            try (InputStream stream = kill.getInputStream()) {
                byte[] buffer = new byte[256];
                while (stream.read(buffer) >= 0) {
                    // 有意丢弃：这里只关心信号是否发出
                }
            }
            kill.waitFor();
        } catch (IOException e) {
            LOG.debug("发送信号失败: pid={} signal={} reason={}", Long.valueOf(pid), signal, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待进程退出。
     *
     * @param process 进程句柄
     * @param millis  等待毫秒数
     * @return 已退出返回 {@code true}
     */
    private static boolean awaitExit(ShellProcess process, long millis) {
        try {
            return process.waitFor(millis) || !process.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /**
     * 读取 {@link Process} 的进程标识。
     *
     * @param process 进程，可为 {@code null}
     * @return 进程标识；取不到时返回 {@code -1}
     */
    static long pidOf(Process process) {
        if (process == null) {
            return -1L;
        }
        Long viaMethod = pidViaMethod(process);
        if (viaMethod != null) {
            return viaMethod.longValue();
        }
        Long viaField = pidViaField(process);
        if (viaField != null) {
            return viaField.longValue();
        }
        long viaText = pidViaToString(process);
        if (viaText > 0) {
            return viaText;
        }
        LOG.debug("无法取得子进程标识，进程树终止将退化为只杀直接子进程");
        return -1L;
    }

    /**
     * 走 Java 9+ 的 {@code Process.pid()} 方法。
     *
     * @param process 进程
     * @return 进程标识；不可用返回 {@code null}
     */
    private static Long pidViaMethod(Process process) {
        try {
            Method method = process.getClass().getMethod("pid");
            Object value = method.invoke(process);
            return value instanceof Long ? (Long) value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 走 Java 8 的私有 {@code pid} 字段。
     *
     * @param process 进程
     * @return 进程标识；不可用返回 {@code null}
     */
    private static Long pidViaField(Process process) {
        try {
            Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return Long.valueOf(field.getLong(process));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Java 9+ 的模块封装会让 setAccessible 抛 InaccessibleObjectException，属预期情况
            return null;
        }
    }

    /**
     * 解析 {@code Process.toString()} 里的 {@code pid=NNN}。
     *
     * @param process 进程
     * @return 进程标识；解析不出来返回 {@code -1}
     */
    private static long pidViaToString(Process process) {
        String text = String.valueOf(process);
        int index = text.indexOf("pid=");
        if (index < 0) {
            return -1L;
        }
        int start = index + "pid=".length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1L;
        }
        try {
            return Long.parseLong(text.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
