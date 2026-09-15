package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 工具 {@code grep_files}：在目录树里按正则逐行搜索文本文件。
 * <p>
 * <b>为什么是逐行正则而不是字面量</b>：这个工具的主要用途是「定位」，模型需要用它把范围收窄
 * （找方法名、找引用、找 TODO），正则比字面量一次性表达力强得多；也正因为它只返回匹配的<b>行</b>，
 * 误匹配的代价很小。
 * <p>
 * 搜索时会跳过版本控制与构建产物目录（{@code .git}、{@code target}、{@code node_modules} 等）：
 * 这些目录里的内容不是「项目的源码」，搜进去只会用噪声淹没结果。跳过名单写在常量里，
 * 不配置化——配置项一多，模型与用户对「为什么搜不到」的预期就会分叉。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class GrepFilesTool implements PluginTool {

    /** 搜索时整体跳过的目录名。 */
    private static final Set<String> SKIPPED_DIRECTORIES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(".git", ".idea", "target", "build", "node_modules")));

    /** 二进制探测读取的字节数。 */
    private static final int BINARY_PROBE_BYTES = 8192;

    /** 缺省最大匹配数。 */
    private static final int DEFAULT_MAX_RESULTS = 100;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "grep_files",
            "在文件或目录树中按正则表达式逐行搜索，返回「文件:行号:该行内容」。会跳过 .git/target/node_modules 等目录。",
            ToolSchema.properties(
                    "pattern", ToolSchema.string("Java 正则表达式，对每一行做查找（不是整文件匹配）"),
                    "path", ToolSchema.string("搜索起点，可以是文件或目录，相对路径按进程工作目录解析；缺省为当前工作目录"),
                    "max_results", ToolSchema.integer("最多返回多少处匹配，缺省 " + DEFAULT_MAX_RESULTS)),
            Arrays.asList("pattern"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Pattern pattern = compile(arguments.requireString("pattern"));
        Path root = ToolPaths.resolve(arguments.optionalString("path", "."));
        int maxResults = arguments.optionalInt("max_results", DEFAULT_MAX_RESULTS);
        if (maxResults < 1) {
            throw new JellyfishException("max_results 必须大于 0: " + maxResults);
        }
        if (!Files.exists(root)) {
            throw new JellyfishException("路径不存在: " + ToolPaths.display(root));
        }
        Searcher searcher = new Searcher(pattern, maxResults);
        try {
            searcher.search(root);
        } catch (IOException e) {
            throw new JellyfishException("搜索失败: " + ToolPaths.display(root) + " (" + e.getMessage() + ')', e);
        }
        return new ToolCallResult(name(), render(searcher));
    }

    /**
     * 编译正则表达式。
     *
     * @param pattern 正则原文
     * @return 已编译的模式
     * @throws JellyfishException 表达式非法时抛出
     */
    private static Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new JellyfishException("正则表达式非法: " + e.getDescription(), e);
        }
    }

    /**
     * 渲染搜索结果。
     *
     * @param searcher 已完成搜索的搜索器
     * @return 结果文本
     */
    private static String render(Searcher searcher) {
        if (searcher.matches.isEmpty()) {
            return "没有匹配到任何内容。";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < searcher.matches.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(searcher.matches.get(i));
        }
        if (searcher.truncated) {
            text.append("\n[已截断：只显示前 ").append(searcher.maxResults)
                    .append(" 处匹配，可收窄 path 或调大 max_results]");
        }
        return text.toString();
    }

    /**
     * 目录树搜索器：以「文件访问者」的形式承载匹配状态，避免把可变状态散在方法参数里。
     */
    private static final class Searcher extends SimpleFileVisitor<Path> {

        /** 已编译的匹配模式。 */
        private final Pattern pattern;

        /** 最大匹配数。 */
        private final int maxResults;

        /** 已收集的匹配行文本。 */
        private final List<String> matches = new ArrayList<String>();

        /** 是否因为达到上限而提前终止。 */
        private boolean truncated;

        /**
         * 构造搜索器。
         *
         * @param pattern    已编译的匹配模式
         * @param maxResults 最大匹配数
         */
        Searcher(Pattern pattern, int maxResults) {
            this.pattern = pattern;
            this.maxResults = maxResults;
        }

        /**
         * 执行搜索：起点是文件时只搜这一个文件，是目录时遍历整棵树。
         *
         * @param root 搜索起点
         * @throws IOException 遍历失败时抛出
         */
        private void search(Path root) throws IOException {
            if (Files.isDirectory(root)) {
                Files.walkFileTree(root, this);
                return;
            }
            searchFile(root);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            Path name = directory.getFileName();
            if (name != null && SKIPPED_DIRECTORIES.contains(name.toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            searchFile(file);
            return truncated ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        /**
         * 搜索单个文件。
         * <p>
         * 读不了的文件（权限、竞态删除）直接跳过：一个不可读文件不该让整次搜索失败。
         *
         * @param file 文件路径
         * @throws IOException 判断文件类型失败时抛出
         */
        private void searchFile(Path file) throws IOException {
            if (!Files.isRegularFile(file) || isBinary(file)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                int lineNumber = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (!pattern.matcher(line).find()) {
                        continue;
                    }
                    if (matches.size() >= maxResults) {
                        // 已经收满，再多看一眼只为确认「还有更多」
                        truncated = true;
                        return;
                    }
                    matches.add(ToolPaths.display(file) + ':' + lineNumber + ':' + line);
                }
            } catch (IOException e) {
                // 单个文件读不了（权限 / 非法 UTF-8 / 中途被删）不影响其余文件
                return;
            }
        }

        /**
         * 判断文件是否为二进制：开头出现 {@code NUL} 就认定不是文本。
         *
         * @param file 文件路径
         * @return 二进制返回 {@code true}
         * @throws IOException 读取失败时抛出
         */
        private static boolean isBinary(Path file) throws IOException {
            byte[] probe = new byte[BINARY_PROBE_BYTES];
            int read;
            try (InputStream input = Files.newInputStream(file)) {
                read = input.read(probe);
            }
            for (int i = 0; i < read; i++) {
                if (probe[i] == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
