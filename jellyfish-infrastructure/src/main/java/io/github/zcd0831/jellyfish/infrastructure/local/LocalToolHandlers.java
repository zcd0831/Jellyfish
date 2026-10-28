package io.github.zcd0831.jellyfish.infrastructure.local;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.infrastructure.file.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 8 个本地能力的处理器：全部先过 {@link Workspace} 的边界校验，再动手。
 * 规则出处：05-action §5.4.1 工具表、§5.8.1 规则 9（硬限制在同一处声明）。
 */
public final class LocalToolHandlers {

    static final String READ_FILE = "read_file";
    static final String LIST_DIR = "list_dir";
    static final String GREP = "grep";
    static final String FETCH_URL = "fetch_url";
    static final String EDIT_FILE = "edit_file";
    static final String WRITE_FILE = "write_file";
    static final String DELETE_FILE = "delete_file";
    static final String RUN_COMMAND = "run_command";

    static final int MAX_READ_LINES = 2000;
    static final int MAX_ENTRIES = 500;
    static final int MAX_HITS = 200;
    static final int MAX_HIT_CHARS = 200;
    static final int DEFAULT_FETCH_CHARS = 8000;
    static final long DEFAULT_TIMEOUT_MS = 120_000;
    static final long MAX_TIMEOUT_MS = 600_000;
    static final int TAIL_LINES = 200;

    private static final List<String> IGNORED_DIRS =
            Arrays.asList(".git", "target", "node_modules", ".idea", ".mvn", "dist", "build");
    private static final java.util.Set<String> BINARY_EXTENSIONS = new java.util.HashSet<String>(Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".zip", ".jar", ".class", ".pdf", ".so", ".dylib",
            ".exe", ".bin", ".woff", ".woff2", ".ttf", ".mp3", ".mp4"));

    private LocalToolHandlers() {
    }

    public static List<InternalToolHandlers.NamedHandler> all(Workspace workspace, UrlFetcher fetcher) {
        return Arrays.asList(
                named(READ_FILE, readFile(workspace)),
                named(LIST_DIR, listDir(workspace)),
                named(GREP, grep(workspace)),
                named(FETCH_URL, fetchUrl(fetcher)),
                named(EDIT_FILE, editFile(workspace)),
                named(WRITE_FILE, writeFile(workspace)),
                named(DELETE_FILE, deleteFile(workspace)),
                named(RUN_COMMAND, runCommand(workspace)));
    }

    private static InternalToolHandlers.NamedHandler named(String name, LocalTool tool) {
        return new InternalToolHandlers.NamedHandler(name, guarded(tool));
    }

    /** 越界与参数问题是"一种工具结果"，不是异常（§7.1 规则 9、规则 3）。 */
    private static InternalToolHandler guarded(final LocalTool tool) {
        return new InternalToolHandler() {
            @Override
            public ToolExecOutcome handle(Session session, Map<String, Object> args) {
                try {
                    return ToolExecOutcome.plain(tool.run(args));
                } catch (Workspace.OutsideWorkspace | IllegalArgumentException e) {
                    return ToolExecOutcome.plain("工具错误：" + e.getMessage());
                } catch (Exception e) {
                    return ToolExecOutcome.plain("工具调用失败：" + e);
                }
            }
        };
    }

    private static LocalTool readFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(string(args, "path"));
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("不是普通文件：" + workspace.relative(path));
                }
                byte[] bytes = Files.readAllBytes(path);
                for (byte b : bytes) {
                    if (b == 0) {
                        throw new IllegalArgumentException("二进制文件，拒绝读取：" + workspace.relative(path));
                    }
                }
                String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
                int offset = Math.max(1, number(args, "offset", 1));
                int limit = Math.min(MAX_READ_LINES, Math.max(1, number(args, "limit", MAX_READ_LINES)));
                int from = Math.min(offset, lines.length);
                int to = Math.min(lines.length, from - 1 + limit);
                StringBuilder out = new StringBuilder();
                for (int i = from; i <= to; i++) {
                    out.append(String.format("%6d\t%s%n", i, stripCarriageReturn(lines[i - 1])));
                }
                if (to < lines.length) {
                    out.append("（共 ").append(lines.length).append(" 行，已截取 ").append(from)
                            .append('-').append(to).append("；用 offset 继续）");
                }
                return out.toString();
            }
        };
    }

    private static LocalTool listDir(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path root = workspace.resolve(optional(args, "path", "."));
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException("不是目录：" + workspace.relative(root));
                }
                int depth = Math.max(1, Math.min(3, number(args, "depth", 2)));
                String glob = optional(args, "pattern", null);
                List<String> entries = new ArrayList<String>();
                collect(root, depth, glob, entries);
                if (entries.isEmpty()) {
                    return "（空目录）";
                }
                StringBuilder out = new StringBuilder();
                int shown = Math.min(entries.size(), MAX_ENTRIES);
                for (int i = 0; i < shown; i++) {
                    out.append(entries.get(i)).append('\n');
                }
                if (entries.size() > shown) {
                    out.append("（共 ").append(entries.size()).append(" 项，只列前 ").append(shown).append(" 项）");
                }
                return out.toString();
            }

            private void collect(Path dir, int depth, String glob, List<String> entries) throws IOException {
                if (depth <= 0 || entries.size() > MAX_ENTRIES * 4) {
                    return;
                }
                List<Path> children = new ArrayList<Path>();
                try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                    stream.forEach(children::add);
                }
                Collections.sort(children);
                for (Path child : children) {
                    if (Files.isDirectory(child)) {
                        if (IGNORED_DIRS.contains(child.getFileName().toString())) {
                            continue;
                        }
                        entries.add(workspace.relative(child) + "/");
                    } else if (glob == null || matches(glob, child)) {
                        entries.add(workspace.relative(child));
                    }
                }
                for (Path child : children) {
                    if (Files.isDirectory(child) && !IGNORED_DIRS.contains(child.getFileName().toString())) {
                        collect(child, depth - 1, glob, entries);
                    }
                }
            }
        };
    }

    private static LocalTool grep(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                final Pattern pattern = compile(string(args, "pattern"), flag(args, "ignoreCase"));
                final Path start = workspace.resolve(optional(args, "path", "."));
                final String glob = optional(args, "glob", null);
                final int context = Math.max(0, Math.min(5, number(args, "context", 0)));
                final List<String> hits = new ArrayList<String>();
                walk(start, glob, new FileConsumer() {
                    @Override
                    public void accept(Path file) throws IOException {
                        if (hits.size() > MAX_HITS) {
                            return;
                        }
                        search(file, pattern, context, hits);
                    }
                });
                if (hits.isEmpty()) {
                    return "（没有匹配）";
                }
                StringBuilder out = new StringBuilder();
                int shown = Math.min(hits.size(), MAX_HITS);
                for (int i = 0; i < shown; i++) {
                    out.append(hits.get(i)).append('\n');
                }
                if (hits.size() > shown) {
                    out.append("（命中超过 ").append(MAX_HITS).append(" 条，已截断）");
                }
                return out.toString();
            }

            private void search(Path file, Pattern pattern, int context, List<String> hits) throws IOException {
                if (isBinary(file)) {
                    return;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!pattern.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    if (hits.size() >= MAX_HITS) {
                        return;
                    }
                    for (int j = Math.max(0, i - context); j < i; j++) {
                        hits.add(hit(file, j + 1, lines.get(j), '-'));
                    }
                    hits.add(hit(file, i + 1, lines.get(i), ':'));
                    for (int j = i + 1; j <= Math.min(lines.size() - 1, i + context); j++) {
                        hits.add(hit(file, j + 1, lines.get(j), '-'));
                    }
                }
            }

            private String hit(Path file, int lineNo, String text, char separator) {
                String trimmed = text.length() <= MAX_HIT_CHARS ? text : text.substring(0, MAX_HIT_CHARS) + "…";
                return workspace.relative(file) + separator + lineNo + separator + trimmed.trim();
            }
        };
    }

    private static LocalTool fetchUrl(final UrlFetcher fetcher) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) {
                String text = fetcher.fetch(string(args, "url"));
                int max = Math.max(200, number(args, "maxChars", DEFAULT_FETCH_CHARS));
                return text.length() <= max ? text : text.substring(0, max) + "\n（已截断，共 " + text.length() + " 字符）";
            }
        };
    }

    private static LocalTool editFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(string(args, "path"));
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("不是普通文件：" + workspace.relative(path));
                }
                String oldText = string(args, "oldText");
                String newText = string(args, "newText");
                if (oldText.isEmpty()) {
                    throw new IllegalArgumentException("oldText 不能为空");
                }
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                int occurrences = count(content, oldText);
                if (occurrences == 0) {
                    throw new IllegalArgumentException("未找到 oldText，请先 read_file 确认原文");
                }
                if (occurrences > 1) {
                    throw new IllegalArgumentException("oldText 出现 " + occurrences
                            + " 次（行 " + lineNumbers(content, oldText) + "），需要更精确的片段");
                }
                AtomicFiles.write(path, content.replace(oldText, newText));
                // 出参是"改动的行数"（05-action §5.4.1）：模型靠它确认改对了地方
                return "已修改第 " + lineNumbers(content, oldText) + " 行：" + workspace.relative(path);
            }
        };
    }

    private static LocalTool writeFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(string(args, "path"));
                workspace.refuseRoot(path);
                boolean overwrite = flag(args, "overwrite");
                if (Files.exists(path) && !overwrite) {
                    throw new IllegalArgumentException("文件已存在，改内容用 edit_file，或显式 overwrite=true");
                }
                String content = optional(args, "content", "");
                AtomicFiles.write(path, content);
                return "已写入 " + workspace.relative(path) + "（" + content.getBytes(StandardCharsets.UTF_8).length
                        + " 字节）";
            }
        };
    }

    private static LocalTool deleteFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(string(args, "path"));
                workspace.refuseRoot(path);
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("不存在：" + workspace.relative(path));
                }
                if (Files.isDirectory(path) && !flag(args, "recursive")) {
                    throw new IllegalArgumentException("是目录，需要 recursive=true：" + workspace.relative(path));
                }
                final int[] removed = {0};
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        removed[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                        Files.delete(dir);
                        removed[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
                return "已删除 " + removed[0] + " 项：" + workspace.relative(path);
            }
        };
    }

    private static LocalTool runCommand(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException, InterruptedException {
                Path cwd = workspace.resolve(optional(args, "cwd", "."));
                if (!Files.isDirectory(cwd)) {
                    throw new IllegalArgumentException("cwd 不是目录：" + workspace.relative(cwd));
                }
                long timeout = Math.min(MAX_TIMEOUT_MS,
                        Math.max(1_000, number(args, "timeoutMs", (int) DEFAULT_TIMEOUT_MS)));
                ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", string(args, "command"));
                builder.directory(cwd.toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                StringBuilder output = new StringBuilder();
                Thread reader = drain(process, output);
                boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (!finished) {
                    kill(process);
                    process.waitFor();
                    reader.join(1_000);
                    return "（超时 " + timeout + "ms，已终止）\n" + tail(output.toString());
                }
                reader.join(2_000);
                return "退出码：" + process.exitValue() + "\n" + tail(output.toString());
            }
        };
    }

    /** 输出必须及时读走，否则管道写满会把子进程卡死在自己身上。 */
    private static Thread drain(final Process process, final StringBuilder output) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[8192];
                try (java.io.InputStream in = process.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        synchronized (output) {
                            output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
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
    private static void kill(Process process) {
        try {
            long pid = pidOf(process);
            if (pid > 0) {
                new ProcessBuilder("pkill", "-TERM", "-P", String.valueOf(pid)).start().waitFor();
            }
        } catch (Exception ignored) {
            // pkill 不存在（非 POSIX）时退化为只杀本体
        }
        process.destroyForcibly();
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

    private static String tail(String output) {
        String[] lines = output.split("\n", -1);
        if (lines.length <= TAIL_LINES) {
            return output;
        }
        StringBuilder out = new StringBuilder("（只显示最后 " + TAIL_LINES + " 行，共 " + lines.length + " 行）\n");
        for (int i = lines.length - TAIL_LINES; i < lines.length; i++) {
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }

    private static void walk(Path start, final String glob, final FileConsumer consumer) throws IOException {
        if (!Files.isDirectory(start)) {
            consumer.accept(start);
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return IGNORED_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (glob == null || matches(glob, file)) {
                    consumer.accept(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean matches(String glob, Path path) {
        return java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob)
                .matches(path.getFileName());
    }

    private static Pattern compile(String regex, boolean ignoreCase) {
        return Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
    }

    private static boolean isBinary(Path file) {
        String name = file.getFileName().toString();
        for (String extension : BINARY_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static int count(String content, String needle) {
        int found = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            found++;
            index = content.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static String lineNumbers(String content, String needle) {
        List<String> numbers = new ArrayList<String>();
        int index = content.indexOf(needle);
        while (index >= 0 && numbers.size() < 5) {
            numbers.add(String.valueOf(1 + count(content.substring(0, index), "\n")));
            index = content.indexOf(needle, index + needle.length());
        }
        return String.join(", ", numbers);
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("参数为空：" + key);
        }
        return text;
    }

    private static String optional(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isEmpty() ? fallback : String.valueOf(value);
    }

    private static int number(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数不是整数：" + key);
        }
    }

    private static boolean flag(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    private interface LocalTool {

        String run(Map<String, Object> args) throws Exception;
    }

    private interface FileConsumer {

        void accept(Path file) throws IOException;
    }
}
