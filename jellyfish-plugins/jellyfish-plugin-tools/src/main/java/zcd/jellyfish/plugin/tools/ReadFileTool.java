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
 * 工具 {@code read_file}：按行范围与字节上限读取文本文件。
 * <p>
 * 刻意<b>不加行号前缀</b>：输出会被模型原样当成文件内容看待，加行号后它很容易把行号
 * 当成内容的一部分，再写回时污染文件；需要定位行号时用 {@code grep_files}。
 * <p>
 * 大文件靠 {@code offset} / {@code limit} 分片读取，而不是截断——截断会让模型以为文件就到那里为止。
 * 命中上限时会在末尾追加一条明确的续读提示。
 * <p>
 * <b>为什么还要 {@code max_bytes}</b>：行数限制管不住「一行很长」的文件（压缩后的 JS、单行 JSON、
 * 日志）。字节上限是本工具自己的默认安全带，让它在绝大多数情况下不需要惊动内核的硬截断；
 * 内核那层仍会再兜一次底，两道都不能省。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class ReadFileTool implements PluginTool {

    /** 缺省最大读取字节数。 */
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;

    /** 工具名片，无状态因此整个插件共用一个实例。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "read_file",
            "读取文本文件内容。大文件可用 offset 与 limit 分片读取，避免一次读入过多内容。",
            ToolSchema.properties(
                    "path", ToolSchema.string("文件路径，相对路径按进程工作目录解析"),
                    "offset", ToolSchema.integer("起始行号，从 1 开始；缺省从第一行开始"),
                    "limit", ToolSchema.integer("最多读取多少行；缺省读到文件末尾"),
                    "max_bytes", ToolSchema.integer("最多读取多少字节，缺省 " + DEFAULT_MAX_BYTES)),
            Arrays.asList("path"),
            true);

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
        int maxBytes = arguments.optionalInt("max_bytes", DEFAULT_MAX_BYTES);
        if (offset < 1) {
            throw new JellyfishException("offset 必须从 1 开始: " + offset);
        }
        if (limit < 0) {
            throw new JellyfishException("limit 不能为负数: " + limit);
        }
        if (maxBytes < 1) {
            throw new JellyfishException("max_bytes 必须大于 0: " + maxBytes);
        }
        requireRegularFile(file);
        return new ToolCallResult(name(), readLines(file, offset, limit, maxBytes));
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
     * 按行范围与字节上限读取文件内容。
     *
     * @param file     文件路径
     * @param offset   起始行号，从 1 开始
     * @param limit    最多读取行数，{@code 0} 表示不限
     * @param maxBytes 最多读取字节数（按 UTF-8 计）
     * @return 文件内容，行间以 {@code \n} 连接
     * @throws JellyfishException 起始行超出文件行数或读取失败时抛出
     */
    private static String readLines(Path file, int offset, int limit, int maxBytes) {
        StringBuilder text = new StringBuilder();
        int lineNumber = 0;
        int taken = 0;
        long usedBytes = 0L;
        boolean moreContent = false;
        boolean lineCut = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) {
                    continue;
                }
                if (limit > 0 && taken >= limit) {
                    // 这一行已经读出来了，说明后面确实还有内容
                    moreContent = true;
                    break;
                }
                long lineBytes = utf8Length(line) + (taken > 0 ? 1 : 0);
                if (usedBytes + lineBytes > maxBytes) {
                    if (taken == 0) {
                        // 首行就超预算：把这一行按字节切短，再继续看下一行以确认「还有没有」
                        line = cutToBytes(line, maxBytes);
                        usedBytes += utf8Length(line);
                        taken++;
                        text.append(line);
                        lineCut = true;
                        continue;
                    }
                    moreContent = true;
                    break;
                }
                if (taken > 0) {
                    text.append('\n');
                }
                text.append(line);
                taken++;
                usedBytes += lineBytes;
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
        if (moreContent || lineCut) {
            appendTruncationHint(text, offset + taken, moreContent, lineCut);
        }
        return text.toString();
    }

    /**
     * 追加截断提示：说明是行被切短还是后面还有内容，并给出可继续读取的偏移。
     *
     * @param text        目标缓冲
     * @param nextOffset  下一条可继续读取的起始行号
     * @param moreContent 后面是否还有未读内容
     * @param lineCut     是否发生了「单行被切短」
     */
    private static void appendTruncationHint(StringBuilder text, int nextOffset, boolean moreContent, boolean lineCut) {
        text.append("\n[已截断：");
        if (lineCut) {
            text.append("单行超过 max_bytes 已截断；");
        }
        if (moreContent) {
            text.append("文件还有更多内容，可用 offset=").append(nextOffset).append(" 继续读取");
        } else {
            text.append("已到文件末尾，如需完整单行请调大 max_bytes");
        }
        text.append(']');
    }

    /**
     * 计算文本的 UTF-8 字节数。
     *
     * @param text 文本
     * @return 字节数
     */
    private static long utf8Length(String text) {
        long bytes = 0L;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            bytes += utf8Length(codePoint);
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    /**
     * 计算单个码点的 UTF-8 字节数。
     *
     * @param codePoint 码点
     * @return 字节数
     */
    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /**
     * 按字节上限把一行切短，切口落在码点边界上。
     *
     * @param line     原始行
     * @param maxBytes 字节上限
     * @return 切短后的行
     */
    private static String cutToBytes(String line, int maxBytes) {
        long bytes = 0L;
        int index = 0;
        while (index < line.length()) {
            int codePoint = line.codePointAt(index);
            long next = bytes + utf8Length(codePoint);
            if (next > maxBytes) {
                break;
            }
            bytes = next;
            index += Character.charCount(codePoint);
        }
        return line.substring(0, index);
    }
}
