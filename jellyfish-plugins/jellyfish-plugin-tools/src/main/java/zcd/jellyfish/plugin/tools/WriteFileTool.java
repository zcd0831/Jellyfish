package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 工具 {@code write_file}：整文件写入（UTF-8，覆盖已有内容）。
 * <p>
 * <b>覆盖语义是刻意的</b>：模型要的是「这个文件现在应该是这样」，而不是「往末尾追加」。
 * 但覆盖会丢内容，因此在输出里明确区分「新建」与「覆盖」——模型据此才能发现自己
 * 本意是改一处、却把整份文件写没了。
 * <p>
 * 只改一处用 {@code edit_file}：它带「匹配数量」校验，比整文件重写安全得多。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class WriteFileTool implements PluginTool {

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "write_file",
            "把内容整体写入文件（UTF-8，会覆盖原内容）。父目录不存在时自动创建；只改一处请用 edit_file。",
            ToolSchema.properties(
                    "path", ToolSchema.string("文件路径，相对路径按进程工作目录解析"),
                    "content", ToolSchema.string("要写入的完整文件内容")),
            Arrays.asList("path", "content"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path file = ToolPaths.resolve(arguments.requireString("path"));
        String content = arguments.requireText("content");
        if (Files.isDirectory(file)) {
            throw new JellyfishException("这是一个目录，无法写入: " + ToolPaths.display(file));
        }
        boolean existed = Files.exists(file);
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JellyfishException("写入文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        return new ToolCallResult(name(), (existed ? "已覆盖写入 " : "已新建文件 ") + ToolPaths.display(file)
                + "（" + bytes + " 字节）");
    }
}
