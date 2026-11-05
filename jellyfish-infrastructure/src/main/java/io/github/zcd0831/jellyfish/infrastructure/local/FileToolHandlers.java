package io.github.zcd0831.jellyfish.infrastructure.local;

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
import java.util.regex.Pattern;

/**
 * 文件与检索类本地能力：read_file / list_dir / grep / edit_file / write_file / delete_file。
 * 每个处理器都先过 {@link Workspace} 的边界校验，软链指向根外一律不列、不递归、不读。
 * 规则出处：05-action §5.4.1 工具表、§5.8.1 规则 9（硬限制）。
 */
final class FileToolHandlers {

    private FileToolHandlers() {
    }

    private static final List<String> IGNORED_DIRS =
            Arrays.asList(".git", "target", "node_modules", ".idea", ".mvn", "dist", "build");
    private static final java.util.Set<String> BINARY_EXTENSIONS = new java.util.HashSet<String>(Arrays.asList(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".zip", ".jar", ".class", ".pdf", ".so", ".dylib",
            ".exe", ".bin", ".woff", ".woff2", ".ttf", ".mp3", ".mp4"));

    static LocalTool readFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                return renderFile(workspace, args);
            }
        };
    }

    static String renderFile(final Workspace workspace, Map<String, Object> args) throws IOException {
        Path path = workspace.resolve(ToolArgs.string(args, "path"));
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
        int offset = Math.max(1, ToolArgs.number(args, "offset", 1));
        int limit = Math.min(LocalToolHandlers.MAX_READ_LINES, Math.max(1, ToolArgs.number(args, "limit", LocalToolHandlers.MAX_READ_LINES)));
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

    static LocalTool listDir(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path root = workspace.resolve(ToolArgs.optional(args, "path", "."));
                if (!Files.isDirectory(root)) {
                    throw new IllegalArgumentException("不是目录：" + workspace.relative(root));
                }
                int depth = Math.max(1, Math.min(3, ToolArgs.number(args, "depth", 2)));
                String glob = ToolArgs.optional(args, "pattern", null);
                List<String> entries = new ArrayList<String>();
                collect(root, depth, glob, entries);
                if (entries.isEmpty()) {
                    return "（空目录）";
                }
                StringBuilder out = new StringBuilder();
                int shown = Math.min(entries.size(), LocalToolHandlers.MAX_ENTRIES);
                for (int i = 0; i < shown; i++) {
                    out.append(entries.get(i)).append('\n');
                }
                if (entries.size() > shown) {
                    out.append("（共 ").append(entries.size()).append(" 项，只列前 ").append(shown).append(" 项）");
                }
                return out.toString();
            }

            private void collect(Path dir, int depth, String glob, List<String> entries) throws IOException {
                if (depth <= 0 || entries.size() > LocalToolHandlers.MAX_ENTRIES * 4) {
                    return;
                }
                List<Path> children = new ArrayList<Path>();
                try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                    stream.forEach(children::add);
                }
                Collections.sort(children);
                for (Path child : children) {
                    if (escapesWorkspace(workspace, child)) {
                        continue;   // 指向根外的软链不列、也不递归（05-action §5.8.1 规则 9）
                    }
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
                    if (escapesWorkspace(workspace, child)) {
                        continue;
                    }
                    if (Files.isDirectory(child) && !IGNORED_DIRS.contains(child.getFileName().toString())) {
                        collect(child, depth - 1, glob, entries);
                    }
                }
            }
        };
    }

    static LocalTool grep(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                final Pattern pattern = compile(ToolArgs.string(args, "pattern"), ToolArgs.flag(args, "ignoreCase"));
                final Path start = workspace.resolve(ToolArgs.optional(args, "path", "."));
                final String glob = ToolArgs.optional(args, "glob", null);
                final int context = Math.max(0, Math.min(5, ToolArgs.number(args, "context", 0)));
                final List<String> hits = new ArrayList<String>();
                walk(workspace, start, glob, new FileConsumer() {
                    @Override
                    public void accept(Path file) throws IOException {
                        if (hits.size() > LocalToolHandlers.MAX_HITS) {
                            return;
                        }
                        search(file, pattern, context, hits);
                    }
                });
                if (hits.isEmpty()) {
                    return "（没有匹配）";
                }
                StringBuilder out = new StringBuilder();
                int shown = Math.min(hits.size(), LocalToolHandlers.MAX_HITS);
                for (int i = 0; i < shown; i++) {
                    out.append(hits.get(i)).append('\n');
                }
                if (hits.size() > shown) {
                    out.append("（命中超过 ").append(LocalToolHandlers.MAX_HITS).append(" 条，已截断）");
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
                    if (hits.size() >= LocalToolHandlers.MAX_HITS) {
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
                String trimmed = text.length() <= LocalToolHandlers.MAX_HIT_CHARS ? text : text.substring(0, LocalToolHandlers.MAX_HIT_CHARS) + "…";
                return workspace.relative(file) + separator + lineNo + separator + trimmed.trim();
            }
        };
    }

    static LocalTool editFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(ToolArgs.string(args, "path"));
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("不是普通文件：" + workspace.relative(path));
                }
                String oldText = ToolArgs.string(args, "oldText");
                String newText = ToolArgs.string(args, "newText");
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

    static LocalTool writeFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(ToolArgs.string(args, "path"));
                workspace.refuseRoot(path);
                boolean overwrite = ToolArgs.flag(args, "overwrite");
                if (Files.exists(path) && !overwrite) {
                    throw new IllegalArgumentException("文件已存在，改内容用 edit_file，或显式 overwrite=true");
                }
                String content = ToolArgs.optional(args, "content", "");
                AtomicFiles.write(path, content);
                return "已写入 " + workspace.relative(path) + "（" + content.getBytes(StandardCharsets.UTF_8).length
                        + " 字节）";
            }
        };
    }

    static LocalTool deleteFile(final Workspace workspace) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) throws IOException {
                Path path = workspace.resolve(ToolArgs.string(args, "path"));
                workspace.refuseRoot(path);
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("不存在：" + workspace.relative(path));
                }
                if (Files.isDirectory(path) && !ToolArgs.flag(args, "recursive")) {
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

    /** 软链指向根外：不列、不递归、不读。规则出处：05-action §5.8.1 规则 9。 */
    private static boolean escapesWorkspace(Workspace workspace, Path path) {
        return Files.isSymbolicLink(path) && !workspace.isInside(path);
    }

    private static void walk(final Workspace workspace, Path start, final String glob,
                             final FileConsumer consumer) throws IOException {
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
                // 软链指向根外就跳过：walkFileTree 不跟随，但 readAllLines 会跟（05-action §5.8.1 规则 9）
                if (escapesWorkspace(workspace, file)) {
                    return FileVisitResult.CONTINUE;
                }
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

    interface FileConsumer {

        void accept(Path file) throws IOException;
    }
}