package io.github.zcd0831.jellyfish.shell.tui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 表格：对齐、对齐标记、CJK 宽度、单元格换行、分不过来就原样。 */
class MarkdownTableTest {

    private static String render(String... lines) {
        List<String> block = Arrays.asList(lines);
        return MarkdownTable.render(block, 80, false);
    }

    @Test
    void 表头与分隔行渲染成对齐两列() {
        String out = render("| 名称 | 说明 |", "| --- | --- |", "| read_file | 读文件 |");

        assertTrue(out.contains("名称"), out);
        assertTrue(out.contains("─"), "应有分隔线：" + out);
        assertTrue(!out.contains("|"), "原始竖线不应出现：" + out);
        assertTrue(out.contains("read_file"), out);
    }

    @Test
    void 中文列按可见宽度对齐() {
        String out = render("| 名 | 说明 |", "| --- | --- |", "| 读写 | 短 |");

        String[] lines = out.split("\n");
        int headerName = lines[0].indexOf("名");
        int cellName = lines[2].indexOf("读");
        assertEquals(headerName, cellName, "中文列起点应对齐：\n" + out);
    }

    @Test
    void 对齐标记生效() {
        String right = render("| 编号 | 名称 |", "| ---: | --- |", "| 1 | a |");
        String left = render("| 编号 | 名称 |", "| --- | --- |", "| 1 | a |");

        assertTrue(!right.equals(left), "右对齐与左对齐应有差异：\n" + right + "\n---\n" + left);
    }

    @Test
    void 单元格过长会换行且行高对齐() {
        String out = MarkdownTable.render(Arrays.asList("| 名称 | 说明 |", "| --- | --- |",
                "| a | 这是一段很长很长的说明文字需要折行 |"), 30, false);

        assertTrue(out.split("\n").length >= 4, "应折成多行：\n" + out);
        assertTrue(!out.contains("|"), "折行后仍应是渲染后的表格：\n" + out);
    }

    @Test
    void 列数不一致就原样输出() {
        String out = render("| a | b |", "| --- | --- |", "| 只有一格 |");

        assertTrue(out.startsWith("| a | b |"), "退化时应原样输出：" + out);
    }

    @Test
    void 转义竖线保留为内容() {
        String out = render("| 表达式 | 含义 |", "| --- | --- |", "| a \\| b | 或 |");

        assertTrue(out.contains("a | b"), out);
    }

    @Test
    void 单元格里的行内代码与粗体被渲染() {
        String colored = MarkdownTable.render(Arrays.asList("| x |", "| --- |", "| `code` |"), 60, true);

        assertTrue(colored.contains("\u001B[36m"), "行内代码应着色：" + colored);
    }

    @Test
    void 太窄放不下就原样输出() {
        String out = MarkdownTable.render(Arrays.asList("| aaaaaaaa | bbbbbbbb | cccccccc |",
                "| --- | --- | --- |", "| 1 | 2 | 3 |"), 10, false);

        assertTrue(out.contains("|"), "分不过来应原样输出：" + out);
    }

    @Test
    void 分隔行识别() {
        assertTrue(MarkdownTable.isSeparator("| --- | :---: |"));
        assertTrue(!MarkdownTable.isSeparator("| 名称 |"));
    }
}
