package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最外层兜底站：领域不变量违反要留证（error 事件 + 现场快照）并给出退出码。
 * 约定出处：Jellyfish/AGENTS.md「错误表达：…冒到最外层写 error 日志 + 现场快照」。
 */
class MainGuardedTest {

    @TempDir
    Path tmp;

    private final List<String> events = new ArrayList<String>();

    private void installCollector() {
        TechnicalLog.use(new TechnicalLog.Sink() {
            @Override
            public void emit(String event, long durationMillis, String... fields) {
                events.add(event + " " + Arrays.toString(fields));
            }
        });
    }

    @AfterEach
    void restoreSink() {
        TechnicalLog.use(new TechnicalLog.Sink() {
            @Override
            public void emit(String event, long durationMillis, String... fields) {
                // 还原成默认的丢弃实现，避免污染其他用例
            }
        });
    }

    @Test
    void 领域不变量违反_记error事件落快照并返回1() throws IOException {
        installCollector();

        int exitCode = Main.guarded(new Runnable() {
            @Override
            public void run() {
                throw new DomainInvariantViolation("步骤已经结案，不能再次开始");
            }
        }, tmp, "/data/demo");

        assertEquals(1, exitCode, "违反领域不变量要以非零码退出");
        assertEquals(1, events.size(), "日志里要有一条 error 事件，否则事后无法定位");
        assertTrue(events.get(0).contains("fatal.domain_invariant"), events.get(0));
        assertTrue(events.get(0).contains("步骤已经结案"), events.get(0));

        Path written = onlyCrashFile();
        String text = new String(Files.readAllBytes(written), StandardCharsets.UTF_8);
        assertTrue(text.contains("DomainInvariantViolation"), text);
        assertTrue(text.contains("步骤已经结案，不能再次开始"), text);
        assertTrue(text.contains("/data/demo"), "现场快照要带数据目录");
        assertTrue(text.contains("---- 栈 ----"), "现场快照要带栈");
    }

    @Test
    void 正常执行返回0且不落快照() throws IOException {
        installCollector();

        assertEquals(0, Main.guarded(new Runnable() {
            @Override
            public void run() {
                // 什么都不做
            }
        }, tmp, "/data/demo"));

        assertEquals(0, events.size());
        assertTrue(!Files.exists(tmp.resolve("crash-file-does-not-matter"))
                && Files.list(tmp).count() == 0, "正常退出不该产生快照");
    }

    @Test
    void 致命Error记录后原样上抛() throws IOException {
        installCollector();

        final OutOfMemoryError fatal = new OutOfMemoryError("Java heap space");
        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Main.guarded(new Runnable() {
                    @Override
                    public void run() {
                        throw fatal;
                    }
                }, tmp, "/data/demo");
            }
        });

        assertTrue(thrown == fatal, "Error 不能在这里被吞掉");
        assertEquals(1, events.size(), "上抛之前也要留证");
        assertTrue(events.get(0).contains("fatal.fatal_error"), events.get(0));
        assertTrue(onlyCrashFile().getFileName().toString().startsWith("crash-"));
    }

    /**
     * 单实例锁：正常退出与异常退出都必须还锁。少了 finally，第二次启动就会被上一次的
     * 残留锁挡在门外——而这条以前没有任何用例守着（R10 阻断 7 的 V4 实验：删掉 release 全绿）。
     * 规则出处：Jellyfish/AGENTS.md「一个数据目录只允许一个实例」。
     */
    @Test
    void 正常退出还锁() throws IOException {
        final boolean[] released = new boolean[1];

        assertEquals(0, Main.withInstanceLock(lock(released), new Runnable() {
            @Override
            public void run() {
                // 什么都不做
            }
        }, DataDirectory.of(tmp.toString())));

        assertTrue(released[0], "跑完不还锁，下次启动会被自己挡住");
    }

    @Test
    void 违反不变量也要还锁() throws IOException {
        installCollector();
        final boolean[] released = new boolean[1];

        assertEquals(1, Main.withInstanceLock(lock(released), new Runnable() {
            @Override
            public void run() {
                throw new DomainInvariantViolation("计划已终止，不能再推进");
            }
        }, DataDirectory.of(tmp.toString())));

        assertTrue(released[0], "异常路径同样要还锁，否则崩溃一次就再也起不来");
    }

    private static io.github.zcd0831.jellyfish.application.shared.port.InstanceLock lock(final boolean[] released) {
        return new io.github.zcd0831.jellyfish.application.shared.port.InstanceLock() {
            @Override
            public void acquire() {
                // 本用例只关心释放
            }

            @Override
            public void release() {
                released[0] = true;
            }
        };
    }

    private Path onlyCrashFile() throws IOException {
        List<Path> files = new ArrayList<Path>();
        java.util.stream.Stream<Path> listing = Files.list(tmp);
        try {
            listing.forEach(files::add);
        } finally {
            listing.close();
        }
        assertEquals(1, files.size(), "应当只落一个现场快照文件：" + files);
        return files.get(0);
    }
}
