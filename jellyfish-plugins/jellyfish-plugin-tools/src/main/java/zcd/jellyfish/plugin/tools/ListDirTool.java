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
 * 输出把目录排在文件之前、并给目录名带上 {@code /} 后缀：模型据此一眼看出该继续往下看
 * 还是可以直接读。<b>不做任何过滤</b>（{@code target}、{@code node_modules} 照常列出）——
 * 「目录里到底有什么」是事实，替模型裁剪只会让它对项目结构形成错误印象。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class ListDirTool implements PluginTool {

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "list_dir",
            "列举一个目录的直接子项（不递归）。目录名以 / 结尾，文件带字节数。",
            ToolSchema.properties(
                    "path", ToolSchema.string("目录路径，相对路径按进程工作目录解析；缺省为当前工作目录")),
            Arrays.<String>asList());

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path directory = ToolPaths.resolve(arguments.optionalString("path", "."));
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
        StringBuilder text = new StringBuilder("目录 ").append(ToolPaths.display(directory))
                .append("（共 ").append(entries.size()).append(" 项）：");
        for (Path entry : entries) {
            text.append('\n').append(render(entry));
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
