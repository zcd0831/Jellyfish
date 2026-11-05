package io.github.zcd0831.jellyfish.infrastructure.local;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * run_command：在 {@link Workspace} 里起一个子进程，只回尾部输出 + 退出码；超时或命中终止令牌就
 * **自上而下**杀整棵进程树。子进程只带白名单环境变量（宿主凭据不进子进程）。
 * 规则出处：05-action §5.4、§5.8.1 规则 9；08-application §8.9.4.1。
 */
final class CommandToolHandler {

    /** 命令超时：默认 120s，上限 600s（05-action §5.8.1 规则 9）。 */
    static final long DEFAULT_TIMEOUT_MS = 120_000;
    static final long MAX_TIMEOUT_MS = 600_000;
    /** 只回尾部 200 行：输出量再大也不进堆（规则 9）。 */
    static final int TAIL_LINES = 200;
    /** 等待子进程的分片：终止信号到达后最多这么久就杀进程树。 */
    static final long KILL_POLL_MILLIS = 100;
    /** TERM 之后给多少宽限再上 KILL：够 shell 跑完 trap，又不至于让用户等。 */
    static final long KILL_GRACE_MILLIS = 500;

    private CommandToolHandler() {
    }

    static LocalTool runCommand(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException, InterruptedException {
                return run(args, CancellationToken.never());
            }

            @Override
            public String run(Map<String, Object> args, CancellationToken token)
                    throws IOException, InterruptedException {
                Path cwd = workspace.resolve(ToolArgs.optional(args, "cwd", "."));
                if (!Files.isDirectory(cwd)) {
                    throw new IllegalArgumentException("cwd 不是目录：" + workspace.relative(cwd));
                }
                long timeout = Math.min(MAX_TIMEOUT_MS,
                        Math.max(1_000, ToolArgs.number(args, "timeoutMs", (int) DEFAULT_TIMEOUT_MS)));
                ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", ToolArgs.string(args, "command"));
                // 子进程不继承宿主环境：凭据类变量一律不传（05-action §5.4 run_command）
                inheritMinimalEnvironment(builder);
                builder.directory(cwd.toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                // 只留尾部：输出量再大，占的内存也不随命令输出增长（§5.8.1 规则 9）
                OutputTail output = new OutputTail(TAIL_LINES);
                Thread reader = drain(process, output);
                // 分片等待：令牌一响就杀进程树，不必等到工具自己的超时（§8.9.4.1）
                long deadline = System.currentTimeMillis() + timeout;
                boolean finished = false;
                while (!finished) {
                    if (token.isCancelled()) {
                        kill(process);
                        process.waitFor();
                        reader.join(1_000);
                        throw new java.util.concurrent.CancellationException("run_command 已被终止");
                    }
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    finished = process.waitFor(Math.min(remaining, KILL_POLL_MILLIS), TimeUnit.MILLISECONDS);
                }
                if (!finished) {
                    kill(process);
                    process.waitFor();
                    reader.join(1_000);
                    return "（超时 " + timeout + "ms，已终止）\n" + output.render();
                }
                reader.join(2_000);
                output.finish();
                return "退出码：" + process.exitValue() + "\n" + output.render();
            }
        };
    }

    /** 输出必须及时读走，否则管道写满会把子进程卡死在自己身上。 */
    private static Thread drain(final Process process, final OutputTail output) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[8192];
                try (java.io.InputStream in = process.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        output.append(buffer, read);
                    }
                } catch (IOException ignored) {
                    // 进程被终止时流会断，这不是错误
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** JDK 8 没有 ProcessHandle.descendants()：先让 pkill 收子进程，再强杀本体。 */
    /**
     * 杀整棵进程树：先拍一张"根→子孙"的快照，再<strong>自上而下</strong>发 TERM，宽限后 KILL。
     * <p>
     * 两个坑：<br>
     * ① 只 `pkill -P pid` 打直接子进程，孙进程（脚本自己再起的子进程）会留下来继续跑；<br>
     * ② 先杀子进程再杀父进程更糟——shell 等到子进程结束会<strong>接着执行下一条命令</strong>
     * （实测 `sh -c "sleep 2; touch 标记"` 会因此真的把标记写出来）。
     * 规则出处：08-application §8.9.4.1（内置工具命中令牌即杀进程树）。
     */
    private static void kill(Process process) {
        long pid = pidOf(process);
        if (pid > 0) {
            for (long target : treeOf(pid)) {
                signal(target, "TERM");
            }
            if (!exitedWithin(process, KILL_GRACE_MILLIS)) {
                for (long target : treeOf(pid)) {
                    signal(target, "KILL");
                }
            }
        }
        process.destroyForcibly();
    }

    /** 根在前、子孙在后：TERM 必须先把父进程打死，否则脚本会继续往下跑。 */
    private static List<Long> treeOf(long pid) {
        List<Long> tree = new ArrayList<Long>();
        tree.add(pid);
        for (long child : childrenOf(pid)) {
            tree.addAll(treeOf(child));
        }
        return tree;
    }

    private static void signal(long pid, String signal) {
        try {
            new ProcessBuilder("kill", "-" + signal, String.valueOf(pid)).start().waitFor();
        } catch (Exception ignored) {
            // 没有 kill 命令（非 POSIX）时由调用方的 destroyForcibly 兜底
        }
    }

    private static List<Long> childrenOf(long pid) {
        List<Long> children = new ArrayList<Long>();
        try {
            Process pgrep = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pgrep.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.matches("\\d+")) {
                        children.add(Long.valueOf(trimmed));
                    }
                }
            }
            pgrep.waitFor();
        } catch (Exception ignored) {
            // pgrep 不可用：退化为只杀本体
        }
        return children;
    }

    private static boolean exitedWithin(Process process, long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long pidOf(Process process) {
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return ((Number) field.get(process)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 子进程只带运行必需的环境变量。宿主凭据（OPENAI_API_KEY 等）不能出现在子进程里 ——
     * 否则 `env` 一条命令就能把密钥打进流水并随下一轮 prompt 出境。
     * 规则出处：05-action §5.4（run_command 子进程不继承宿主环境）。
     */
    private static final List<String> ENV_WHITELIST = Arrays.asList(
            "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "SHELL", "TERM", "USER",
            "JAVA_HOME", "M2_HOME", "MAVEN_OPTS", "GRADLE_USER_HOME", "NVM_DIR");

    private static void inheritMinimalEnvironment(ProcessBuilder builder) {
        builder.environment().clear();
        builder.environment().putAll(minimalEnvironment(System.getenv()));
    }

    /** 纯函数：从宿主环境里筛出白名单项。便于直接断言"凭据被丢掉"。 */
    static Map<String, String> minimalEnvironment(Map<String, String> host) {
        Map<String, String> kept = new java.util.LinkedHashMap<String, String>();
        for (String name : ENV_WHITELIST) {
            String value = host.get(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        return kept;
    }
}
