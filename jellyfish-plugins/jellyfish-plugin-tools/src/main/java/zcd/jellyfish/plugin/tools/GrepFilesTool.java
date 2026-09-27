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
 * <b>三重上限</b>：匹配数（{@code max_results}）、单行长度（{@code max_line_chars}）与总字节数
 * （{@code max_bytes}）。少任何一项都能把结果撑爆——一个压缩过的单行 JS 里搜一个常见词，
 * 一行就够长了；一百处这种匹配又能把一百行拼成一份巨大的输出。
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

    /** 缺省单行最大显示字符数。 */
    private static final int DEFAULT_MAX_LINE_CHARS = 500;

    /** 缺省结果总字节上限。 */
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "grep_files",
            "在文件或目录树中按正则表达式逐行搜索，返回「文件:行号:该行内容」。会跳过 .git/target/node_modules 等目录。",
            ToolSchema.properties(
                    "pattern", ToolSchema.string("Java 正则表达式，对每一行做查找（不是整文件匹配）"),
                    "path", ToolSchema.string("搜索起点，可以是文件或目录，相对路径按进程工作目录解析；缺省为当前工作目录"),
                    "max_results", ToolSchema.integer("最多返回多少处匹配，缺省 " + DEFAULT_MAX_RESULTS),
                    "max_line_chars", ToolSchema.integer("单行最多显示多少字符，超出部分截尾，缺省 "
                            + DEFAULT_MAX_LINE_CHARS),
                    "max_bytes", ToolSchema.integer("结果总字节上限，缺省 " + DEFAULT_MAX_BYTES)),
            Arrays.asList("pattern"),
            true);

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
        int maxLineChars = arguments.optionalInt("max_line_chars", DEFAULT_MAX_LINE_CHARS);
        int maxBytes = arguments.optionalInt("max_bytes", DEFAULT_MAX_BYTES);
        if (maxResults < 1) {
            throw new JellyfishException("max_results 必须大于 0: " + maxResults);
        }
        if (maxLineChars < 1) {
            throw new JellyfishException("max_line_chars 必须大于 0: " + maxLineChars);
        }
        if (maxBytes < 1) {
            throw new JellyfishException("max_bytes 必须大于 0: " + maxBytes);
        }
        if (!Files.exists(root)) {
            throw new JellyfishException("路径不存在: " + ToolPaths.display(root));
        }
        Searcher searcher = new Searcher(pattern, maxResults, maxLineChars, maxBytes);
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
            if (searcher.truncated) {
                // 保留这条分支是为了不把「有匹配但一处都放不下」说成「没有匹配」——那是最误导的一种回答
                return "[已截断：结果超过 max_bytes，没有可显示的匹配；请收窄 path 或调小匹配范围]";
            }
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
            text.append("\n[已截断：只显示前 ").append(searcher.matches.size())
                    .append(" 处匹配，可收窄 path 或调大 max_results / max_bytes]");
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

        /** 单行最大显示字符数。 */
        private final int maxLineChars;

        /** 结果总字节上限。 */
        private final int maxBytes;

        /** 已收集的匹配行文本。 */
        private final List<String> matches = new ArrayList<String>();

        /** 已收集结果的 UTF-8 字节数。 */
        private long usedBytes;

        /** 是否因为达到上限而提前终止。 */
        private boolean truncated;

        /**
         * 构造搜索器。
         *
         * @param pattern      已编译的匹配模式
         * @param maxResults   最大匹配数
         * @param maxLineChars 单行最大显示字符数
         * @param maxBytes     结果总字节上限
         */
        Searcher(Pattern pattern, int maxResults, int maxLineChars, int maxBytes) {
            this.pattern = pattern;
            this.maxResults = maxResults;
            this.maxLineChars = maxLineChars;
            this.maxBytes = maxBytes;
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
                    String match = ToolPaths.display(file) + ':' + lineNumber + ':' + truncate(line, maxLineChars);
                    long cost = utf8Length(match) + 1L;
                    // 字节上限同样属于「收满」：已经收了一整套结果，再添一处就超预算；
                    // 但至少放行第一处——一处都不给，模型就完全看不到为什么截断了
                    if (!matches.isEmpty() && usedBytes + cost > maxBytes) {
                        truncated = true;
                        return;
                    }
                    matches.add(match);
                    usedBytes += cost;
                }
            } catch (IOException e) {
                // 单个文件读不了（权限 / 非法 UTF-8 / 中途被删）不影响其余文件
                return;
            }
        }

        /**
         * 按字符上限截断匹配行，切口落在码点边界上。
         *
         * @param line     匹配行原文
         * @param maxChars 字符上限
         * @return 原行；超长时返回截尾结果
         */
        private static String truncate(String line, int maxChars) {
            if (line.length() <= maxChars) {
                return line;
            }
            int end = maxChars;
            if (Character.isHighSurrogate(line.charAt(end - 1))) {
                end--;
            }
            return line.substring(0, end) + "…";
        }

        /**
         * 计算文本的 UTF-8 字节数。
         *
         * @param text 文本
         * @return 字节数
         */
        private static long utf8Length(String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
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
