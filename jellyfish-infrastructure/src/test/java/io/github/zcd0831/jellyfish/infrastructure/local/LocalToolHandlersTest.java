package io.github.zcd0831.jellyfish.infrastructure.local;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地能力：边界、硬限制、以及"失败也是结果"（05-action §5.8.1 规则 9）。 */
class LocalToolHandlersTest {

    @TempDir
    Path temp;

    private Workspace workspace;
    private Map<String, InternalToolHandler> tools;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Workspace.at(temp);
        tools = new HashMap<String, InternalToolHandler>();
        for (InternalToolHandlers.NamedHandler named
                : LocalToolHandlers.all(workspace, new UrlFetcher())) {
            tools.put(name(named), handler(named));
        }
    }

    @AfterEach
    void tearDown() {
        tools.clear();
    }

    @Test
    void 越界路径被拒且是普通结果() throws IOException {
        Files.write(temp.resolve("inside.txt"), "hi".getBytes(StandardCharsets.UTF_8));

        assertTrue(call("read_file", "path", "inside.txt").contains("hi"));
        assertTrue(call("read_file", "path", "../outside.txt").contains("超出工作区"));
        assertTrue(call("read_file", "path", "/etc/hosts").contains("超出工作区"));
    }

    @Test
    void 符号链接逃逸被拒() throws IOException {
        Path outside = Files.createTempDirectory("jellyfish-outside");
        Files.write(outside.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(temp.resolve("link"), outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        assertTrue(call("read_file", "path", "link/secret.txt").contains("超出工作区"));
    }

    @Test
    void 读文件带行号与分页() throws IOException {
        Files.write(temp.resolve("a.txt"), "l1\nl2\nl3\n".getBytes(StandardCharsets.UTF_8));

        String all = call("read_file", "path", "a.txt");
        assertTrue(all.contains("1\tl1"));
        Map<String, Object> args = map("path", "a.txt", "offset", 2, "limit", 1);
        String page = tools.get("read_file").handle(null, args).content();
        assertTrue(page.contains("2\tl2"));
        assertFalse(page.contains("3\tl3"));
    }

    @Test
    void 二进制文件被拒() throws IOException {
        Files.write(temp.resolve("b.bin"), new byte[] {1, 0, 2});

        assertTrue(call("read_file", "path", "b.bin").contains("二进制"));
    }

    @Test
    void 列目录忽略构建产物() throws IOException {
        Files.createDirectories(temp.resolve("target/classes"));
        Files.createDirectories(temp.resolve("src"));
        Files.write(temp.resolve("src/Main.java"), "x".getBytes(StandardCharsets.UTF_8));

        String out = call("list_dir", "path", ".");

        assertTrue(out.contains("src/Main.java"));
        assertFalse(out.contains("target/"));
    }

    @Test
    void 搜索返回文件与行号() throws IOException {
        Files.write(temp.resolve("a.txt"), "one\nneedle here\nthree\n".getBytes(StandardCharsets.UTF_8));

        String out = call("grep", "pattern", "needle");

        assertTrue(out.contains("a.txt:2:needle here"));
    }

    @Test
    void 替换要求原文恰好一处() throws IOException {
        Path file = temp.resolve("a.txt");
        Files.write(file, "x\ny\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(call("edit_file", "path", "a.txt", "oldText", "nope", "newText", "n")
                .contains("未找到 oldText"));

        Files.write(file, "same\nsame\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(call("edit_file", "path", "a.txt", "oldText", "same", "newText", "n")
                .contains("出现 2 次"));

        Files.write(file, "x\ny\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> args = map("path", "a.txt", "oldText", "y", "newText", "z");
        assertTrue(tools.get("edit_file").handle(null, args).content().contains("已修改第 2 行"));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).equals("x\nz\n"));
    }

    @Test
    void 写文件不覆盖既有内容() throws IOException {
        Files.write(temp.resolve("a.txt"), "keep".getBytes(StandardCharsets.UTF_8));

        assertTrue(call("write_file", "path", "a.txt", "content", "new").contains("已存在"));
        assertTrue(call("write_file", "path", "a.txt", "content", "new", "overwrite", true).contains("已写入"));
        assertTrue(call("write_file", "path", "deep/dir/b.txt", "content", "x").contains("已写入"));
        assertTrue(Files.exists(temp.resolve("deep/dir/b.txt")));
    }

    @Test
    void 删目录必须显式递归且不能删根() throws IOException {
        Files.createDirectories(temp.resolve("build/sub"));
        Files.write(temp.resolve("build/sub/x.txt"), "x".getBytes(StandardCharsets.UTF_8));

        assertTrue(call("delete_file", "path", "build").contains("recursive=true"));
        assertTrue(call("delete_file", "path", ".").contains("工作区根"));
        Map<String, Object> args = map("path", "build", "recursive", true);
        assertTrue(tools.get("delete_file").handle(null, args).content().contains("已删除"));
        assertFalse(Files.exists(temp.resolve("build")));
    }

    @Test
    void 执行命令返回退出码与输出() {
        String ok = call("run_command", "command", "echo hi");
        assertTrue(ok.contains("退出码：0"));
        assertTrue(ok.contains("hi"));
        assertTrue(call("run_command", "command", "exit 3").contains("退出码：3"));
    }

    @Test
    void 超时终止进程() {
        Map<String, Object> args = map("command", "sleep 5", "timeoutMs", 1000);

        String out = tools.get("run_command").handle(null, args).content();

        assertTrue(out.contains("超时"), out);
    }

    @Test
    void 终止信号到达时立即杀掉进程树() throws Exception {
        io.github.zcd0831.jellyfish.application.planning.SessionCancellations cancellations =
                new io.github.zcd0831.jellyfish.application.planning.SessionCancellations();
        final io.github.zcd0831.jellyfish.application.shared.CancellationToken token =
                cancellations.register(io.github.zcd0831.jellyfish.domain.shared.SessionId.of("s1"), null);
        Thread terminator = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                cancellations.cancel(io.github.zcd0831.jellyfish.domain.shared.SessionId.of("s1"));
            }
        });
        terminator.start();
        long started = System.currentTimeMillis();

        // 工具自带超时 30s，但终止信号应让它 1s 内就收手
        Map<String, Object> args = map("command", "sleep 30", "timeoutMs", 30000);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> tools.get("run_command").handle(null, args, token));
        terminator.join();
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < 3000, "终止应在 3s 内生效，实际 " + elapsed + "ms");
    }

    @Test
    void 命令的工作目录限在根内() {
        assertTrue(call("run_command", "command", "pwd", "cwd", "..").contains("超出工作区"));
    }

    private String call(String tool, Object... keyValues) {
        return tools.get(tool).handle(null, map(keyValues)).content();
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return args;
    }

    private static String name(InternalToolHandlers.NamedHandler handler) {
        try {
            java.lang.reflect.Field field = handler.getClass().getDeclaredField("name");
            field.setAccessible(true);
            return (String) field.get(handler);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static InternalToolHandler handler(InternalToolHandlers.NamedHandler named) {
        try {
            java.lang.reflect.Field field = named.getClass().getDeclaredField("handler");
            field.setAccessible(true);
            return (InternalToolHandler) field.get(named);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
