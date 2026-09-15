package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 工具 {@code read_file}：按行范围读取文本文件。
 * <p>
 * 刻意<b>不加行号前缀</b>：输出会被模型原样当成文件内容看待，加行号后它很容易把行号
 * 当成内容的一部分，再写回时污染文件；需要定位行号时用 {@code grep_files}。
 * <p>
 * 大文件靠 {@code offset} / {@code limit} 分片读取，而不是截断——截断会让模型以为文件就到那里为止。
 * 命中 {@code limit} 时会在末尾追加一条明确的续读提示。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class ReadFileTool implements PluginTool {

    /** 工具名片，无状态因此整个插件共用一个实例。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "read_file",
            "读取文本文件内容。大文件可用 offset 与 limit 分片读取，避免一次读入过多内容。",
            ToolSchema.properties(
                    "path", ToolSchema.string("文件路径，相对路径按进程工作目录解析"),
                    "offset", ToolSchema.integer("起始行号，从 1 开始；缺省从第一行开始"),
                    "limit", ToolSchema.integer("最多读取多少行；缺省读到文件末尾")),
            Arrays.asList("path"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path file = ToolPaths.resolve(arguments.requireString("path"));
        int offset = arguments.optionalInt("offset", 1);
        int limit = arguments.optionalInt("limit", 0);
        if (offset < 1) {
            throw new JellyfishException("offset 必须从 1 开始: " + offset);
        }
        if (limit < 0) {
            throw new JellyfishException("limit 不能为负数: " + limit);
        }
        requireRegularFile(file);
        return new ToolCallResult(name(), readLines(file, offset, limit));
    }

    /**
     * 校验路径是可读的普通文件。
     *
     * @param file 文件路径
     * @throws JellyfishException 不存在、是目录或不是普通文件时抛出
     */
    private static void requireRegularFile(Path file) {
        if (!Files.exists(file)) {
            throw new JellyfishException("文件不存在: " + ToolPaths.display(file));
        }
        if (Files.isDirectory(file)) {
            throw new JellyfishException("这是一个目录，请改用 list_dir: " + ToolPaths.display(file));
        }
        if (!Files.isRegularFile(file)) {
            throw new JellyfishException("不是普通文件: " + ToolPaths.display(file));
        }
    }

    /**
     * 按行范围读取文件内容。
     *
     * @param file   文件路径
     * @param offset 起始行号，从 1 开始
     * @param limit  最多读取行数，{@code 0} 表示不限
     * @return 文件内容，行间以 {@code \n} 连接
     * @throws JellyfishException 起始行超出文件行数或读取失败时抛出
     */
    private static String readLines(Path file, int offset, int limit) {
        StringBuilder text = new StringBuilder();
        int lineNumber = 0;
        int taken = 0;
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) {
                    continue;
                }
                if (limit > 0 && taken >= limit) {
                    // 这一行已经读出来了，说明后面确实还有内容
                    truncated = true;
                    break;
                }
                if (taken > 0) {
                    text.append('\n');
                }
                text.append(line);
                taken++;
            }
        } catch (IOException e) {
            throw new JellyfishException("读取文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
        if (taken == 0) {
            if (lineNumber == 0) {
                // 空文件不是错误，「什么都没有」本身就是答案
                return "（文件为空）";
            }
            throw new JellyfishException("起始行超出文件行数: offset=" + offset + "，文件共 " + lineNumber + " 行");
        }
        if (truncated) {
            text.append("\n[已截断：文件还有更多内容，可用 offset=").append(offset + taken).append(" 继续读取]");
        }
        return text.toString();
    }
}
