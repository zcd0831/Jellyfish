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
 * 工具 {@code edit_file}：按原文精确匹配替换，只改文件的一处（或若干处）。
 * <p>
 * <b>为什么要校验匹配数量</b>：模型写出的「原文」常常是文件里很常见的一小段（比如一个变量名），
 * 直接替换会把不相关的地方一起改掉。匹配到多处而调用方没显式声明 {@code replace_all} 时报错，
 * 逼它把上下文补足——这条错误信息比一次错误改写便宜得多。
 * <p>
 * <b>为什么用字面量匹配而不是正则</b>：被改的是代码，里面的 {@code .}、{@code (}、{@code $}
 * 全是普通字符；一旦按正则解释，模型必须自己转义，错一次就改坏文件。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class EditFileTool implements PluginTool {

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "edit_file",
            "在文件中精确替换一段原文（字面量匹配，按 UTF-8 读写）。原文匹配到多处时必须显式声明 replace_all。",
            ToolSchema.properties(
                    "path", ToolSchema.string("文件路径，相对路径按进程工作目录解析"),
                    "old_text", ToolSchema.string("要被替换的原文，必须与文件中完全一致（含缩进）"),
                    "new_text", ToolSchema.string("替换后的新文本，可以是空串（等于删除）"),
                    "replace_all", ToolSchema.bool("原文匹配到多处时是否全部替换，缺省 false")),
            Arrays.asList("path", "old_text", "new_text"));

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ToolArguments arguments = new ToolArguments(request.getArguments());
        Path file = ToolPaths.resolve(arguments.requireString("path"));
        String oldText = arguments.requireText("old_text");
        String newText = arguments.requireText("new_text");
        boolean replaceAll = arguments.optionalBoolean("replace_all", false);
        if (oldText.isEmpty()) {
            throw new JellyfishException("old_text 不能为空；要整文件重写请用 write_file");
        }
        if (oldText.equals(newText)) {
            throw new JellyfishException("old_text 与 new_text 相同，无需修改");
        }
        String content = readText(file);
        int matches = countMatches(content, oldText);
        if (matches == 0) {
            throw new JellyfishException("文件中未找到 old_text 匹配内容，请确认原文（缩进与换行必须完全一致）: "
                    + ToolPaths.display(file));
        }
        if (matches > 1 && !replaceAll) {
            throw new JellyfishException("old_text 在文件中匹配到 " + matches
                    + " 处，请补足上下文使其唯一，或设置 replace_all=true 全部替换");
        }
        String replaced = replaceAll ? content.replace(oldText, newText) : replaceOnce(content, oldText, newText);
        try {
            Files.write(file, replaced.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JellyfishException("写入文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
        return new ToolCallResult(name(), "已在 " + ToolPaths.display(file) + " 替换 " + matches + " 处");
    }

    /**
     * 读取文件全部文本。
     *
     * @param file 文件路径
     * @return 文件内容
     * @throws JellyfishException 文件不存在、是目录或读取失败时抛出
     */
    private static String readText(Path file) {
        if (!Files.exists(file)) {
            throw new JellyfishException("文件不存在: " + ToolPaths.display(file));
        }
        if (Files.isDirectory(file)) {
            throw new JellyfishException("这是一个目录，无法编辑: " + ToolPaths.display(file));
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("读取文件失败: " + ToolPaths.display(file) + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 统计字面量在文本中出现的次数，不重复计数重叠部分。
     *
     * @param content 文本
     * @param needle  待查找的字面量，保证非空
     * @return 出现次数
     */
    private static int countMatches(String content, String needle) {
        int count = 0;
        int index = content.indexOf(needle);
        while (index >= 0) {
            count++;
            index = content.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /**
     * 只替换第一次出现的字面量。
     *
     * @param content 文本
     * @param oldText 原文
     * @param newText 新文本
     * @return 替换后的文本
     */
    private static String replaceOnce(String content, String oldText, String newText) {
        int index = content.indexOf(oldText);
        return content.substring(0, index) + newText + content.substring(index + oldText.length());
    }
}
