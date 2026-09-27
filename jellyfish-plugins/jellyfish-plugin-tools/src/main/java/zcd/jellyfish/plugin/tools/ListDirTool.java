package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 工具 {@code list_dir}：列举一个目录的直接子项。
 * <p>
 * <b>只列一层、不递归</b>：递归列举既容易因为目录树太大把输出撑爆，也让模型失去「逐层探索」的控制权。
 * 需要跨目录找东西时用 {@code grep_files}。
 * <p>
 * <b>默认分页</b>：大目录用 {@code offset} / {@code limit} 分页，命中上限时给出续页提示。
 * 一个有几万项的目录一次性列出来，只会把上下文占满却什么也说明不了。
 * <p>
 * 输出把目录排在文件之前、并给目录名带上 {@code /} 后缀：模型据此一眼看出该继续往下看
 * 还是可以直接读。<b>不做任何过滤</b>（{@code target}、{@code node_modules} 照常列出）——
 * 「目录里到底有什么」是事实，替模型裁剪只会让它对项目结构形成错误印象。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class ListDirTool implements PluginTool {

    /** 缺省每页最多返回的条目数。 */
    private static final int DEFAULT_LIMIT = 200;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "list_dir",
            "列举一个目录的直接子项（不递归）。目录名以 / 结尾，文件带字节数。大目录可用 offset 与 limit 分页。",
            ToolSchema.properties(
                    "path", ToolSchema.string("目录路径，相对路径按进程工作目录解析；缺省为当前工作目录"),
                    "offset", ToolSchema.integer("起始条目序号，从 1 开始；缺省从第一项开始"),
                    "limit", ToolSchema.integer("每页最多返回多少项，缺省 " + DEFAULT_LIMIT)),
            Arrays.<String>asList(),
            true);

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path directory = ToolPaths.resolve(arguments.optionalString("path", "."));
        int offset = arguments.optionalInt("offset", 1);
        int limit = arguments.optionalInt("limit", DEFAULT_LIMIT);
        if (offset < 1) {
            throw new JellyfishException("offset 必须从 1 开始: " + offset);
        }
        if (limit < 1) {
            throw new JellyfishException("limit 必须大于 0: " + limit);
        }
        if (!Files.exists(directory)) {
            throw new JellyfishException("目录不存在: " + ToolPaths.display(directory));
        }
        if (!Files.isDirectory(directory)) {
            throw new JellyfishException("不是目录，请改用 read_file: " + ToolPaths.display(directory));
        }
        List<Path> entries = listEntries(directory);
        if (entries.isEmpty()) {
            return new ToolCallResult(name(), "目录 " + ToolPaths.display(directory) + " 是空目录");
        }
        // 目录优先、同类按名字排序：让「该往下走」这件事在输出里一眼可见
        entries.sort(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                .thenComparing(path -> fileName(path), String.CASE_INSENSITIVE_ORDER));
        if (offset > entries.size()) {
            throw new JellyfishException("offset 超出目录项数: offset=" + offset
                    + "，目录共 " + entries.size() + " 项");
        }
        int from = offset - 1;
        int to = (int) Math.min((long) from + limit, entries.size());
        StringBuilder text = new StringBuilder("目录 ").append(ToolPaths.display(directory))
                .append("（共 ").append(entries.size()).append(" 项，显示第 ")
                .append(from + 1).append('-').append(to).append(" 项）：");
        for (int index = from; index < to; index++) {
            text.append('\n').append(render(entries.get(index)));
        }
        if (to < entries.size()) {
            text.append("\n[已截断：还有 ").append(entries.size() - to)
                    .append(" 项未显示，可用 offset=").append(to + 1).append(" 继续（limit=").append(limit)
                    .append("）]");
        }
        return new ToolCallResult(name(), text.toString());
    }

    /**
     * 读取目录的直接子项。
     *
     * @param directory 目录路径
     * @return 子项列表
     * @throws JellyfishException 读取失败时抛出
     */
    private static List<Path> listEntries(Path directory) {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException e) {
            throw new JellyfishException("列举目录失败: " + ToolPaths.display(directory)
                    + " (" + e.getMessage() + ')', e);
        }
        return entries;
    }

    /**
     * 渲染单个子项。
     *
     * @param entry 子项路径
     * @return 一行文本
     */
    private static String render(Path entry) {
        String fileName = fileName(entry);
        if (Files.isDirectory(entry)) {
            return "d  " + fileName + '/';
        }
        long size;
        try {
            size = Files.size(entry);
        } catch (IOException e) {
            // 大小读不出来不影响「这里有个文件」这个事实，退化成不显示大小
            return "f  " + fileName;
        }
        return "f  " + fileName + "  (" + size + " 字节)";
    }

    /**
     * 取文件名，根路径退化为其自身文本。
     *
     * @param path 路径
     * @return 文件名
     */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}
