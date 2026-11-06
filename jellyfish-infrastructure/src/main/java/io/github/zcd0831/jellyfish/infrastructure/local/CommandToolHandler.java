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
    /** 补扫轮数：快照之后脚本仍可能 fork，扫一次会偶发漏杀。 */
    static final int SWEEP_ROUNDS = 6;
    static final long SWEEP_PAUSE_MILLIS = 60;

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

    /**
     * 杀整棵进程树：先拍"根→子孙"快照并**从根往下**发 TERM，再反复扫几轮补上"快照之后才 fork 出来的"子孙。
     * <p>
     * 三个坑：<br>
     * ① 只 `pkill -P pid` 打直接子进程，孙进程会留下来继续跑；<br>
     * ② 先杀子进程再杀父进程更糟——shell 等到子进程结束会<strong>接着执行下一条命令</strong>
     * （实测 `sh -c "sleep 2; touch 标记"` 会因此真的把标记写出来）；<br>
     * ③ 只扫一次也不够：快照与发信号之间脚本仍可能 fork（实测偶发漏杀，孙进程写出了标记）。
     * 所以按"根先死 + 多轮补扫"来收：根一死就没有"接着跑下一条"的机会，剩下的只是把
     * 快照之后冒出来的子孙找出来打死。
     * 规则出处：08-application §8.9.4.1（内置工具命中令牌即杀进程树）。
     */
    private static void kill(Process process) {
        long pid = pidOf(process);
        if (pid <= 0) {
            process.destroyForcibly();
            return;
        }
        List<Long> known = new ArrayList<Long>();   // 父在前、子在后
        collectTree(pid, known);
        // 根先死：它一旦有权继续，就会接着执行下一条命令（那正是"标记还是被写出来"的来源）
        signal(java.util.Collections.singletonList(pid), "TERM");
        for (int round = 0; round < SWEEP_ROUNDS; round++) {
            boolean grown = collectNewDescendants(known);
            // 一次 kill 打一串 pid（仍然父在前）：每个 pid 单独起进程的话，机器一忙，
            // 杀完半棵树就可能已经过去几百毫秒，来不及拦住孙进程手里的 sleep
            signal(known, round == 0 ? "TERM" : "KILL");
            if (!grown && exitedWithin(process, KILL_GRACE_MILLIS)) {
                break;
            }
            pause(SWEEP_PAUSE_MILLIS);
        }
        signal(known, "KILL");   // 收尾一律 KILL：TERM 被忽略的进程不能留
        process.destroyForcibly();
    }

    /** 把 known 里每个进程新出现的子孙追加进去（保持"父在前"的顺序），返回是否长出了新 pid。 */
    private static boolean collectNewDescendants(List<Long> known) {
        boolean grown = false;
        for (long each : new ArrayList<Long>(known)) {
            for (long child : childrenOf(each)) {
                if (!known.contains(child)) {
                    known.add(child);
                    grown = true;
                }
            }
        }
        return grown;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void collectTree(long pid, List<Long> into) {
        into.add(pid);
        for (long child : childrenOf(pid)) {
            collectTree(child, into);
        }
    }

    /** 一条 `kill` 打一串 pid（顺序保留，父仍在子前）：起进程本身有成本，忙时这个成本会误事。 */
    private static void signal(List<Long> pids, String signal) {
        if (pids.isEmpty()) {
            return;
        }
        List<String> command = new ArrayList<String>();
        command.add("kill");
        command.add("-" + signal);
        for (long pid : pids) {
            command.add(String.valueOf(pid));
        }
        try {
            new ProcessBuilder(command).start().waitFor();
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
