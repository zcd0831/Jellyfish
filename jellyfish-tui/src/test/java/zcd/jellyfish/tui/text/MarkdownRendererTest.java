package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarkdownRenderer} 的单元测试。
 * <p>
 * 断言一律基于纯文本与显示宽度，不涉及终端：渲染是纯函数，因此「屏幕上会出现几行、每行多宽」
 * 这两件最容易出错的事都能在这里钉死。
 *
 * @author zcd
 */
@DisplayName("MarkdownRenderer markdown 渲染")
class MarkdownRendererTest {

    /** 常规测试宽度。 */
    private static final int WIDTH = 40;

    @Test
    @DisplayName("空内容不产生任何行：调用方据此不占版面")
    void render_should_returnEmpty_when_contentBlank() {
        assertTrue(MarkdownRenderer.render(null, WIDTH, Style.EMPTY).isEmpty());
        assertTrue(MarkdownRenderer.render("", WIDTH, Style.EMPTY).isEmpty());
        assertTrue(MarkdownRenderer.render("  \n \t \n", WIDTH, Style.EMPTY).isEmpty());
    }

    @Test
    @DisplayName("段落去掉 markdown 标记，只留可见文本")
    void render_should_stripInlineMarkers() {
        List<String> lines = texts(render("普通**加粗**与*斜体*与~~删除~~与`代码`。"));

        assertEquals(java.util.Collections.singletonList("普通加粗与斜体与删除与代码。"), lines);
    }

    @Test
    @DisplayName("行内标记转成语义样式而不是颜色常量")
    void render_should_styleInlineNodes() {
        // When：一段里依次是加粗、斜体、删除线、行内代码
        List<VisualLine> lines = render("**粗**\n\n*斜*\n\n~~删~~\n\n`码`");

        // Then：样式分别是 bold / italic / crossedOut / yellow
        assertTrue(styles(lines, 0).get(0).effectiveModifiers().contains(dev.tamboui.style.Modifier.BOLD), String.valueOf(styles(lines, 0)));
        assertTrue(styles(lines, 2).get(0).effectiveModifiers().contains(dev.tamboui.style.Modifier.ITALIC), String.valueOf(styles(lines, 2)));
        assertTrue(styles(lines, 4).get(0).effectiveModifiers().contains(dev.tamboui.style.Modifier.CROSSED_OUT), String.valueOf(styles(lines, 4)));
        assertEquals(java.util.Optional.of(dev.tamboui.style.Color.YELLOW),
                styles(lines, 6).get(0).fg());
    }

    @Test
    @DisplayName("标题带块前缀并加粗，一级二级再给强调色")
    void render_should_prefixAndBoldHeadings() {
        List<VisualLine> lines = render("# 一级\n\n### 三级");

        assertEquals(java.util.Arrays.asList("\u258c 一级", "", "\u258c 三级"), texts(lines));
        assertTrue(styles(lines, 0).get(0).effectiveModifiers().contains(dev.tamboui.style.Modifier.BOLD));
        assertEquals(java.util.Optional.of(dev.tamboui.style.Color.CYAN), styles(lines, 0).get(0).fg());
        // 三级标题不再给颜色：层级太深时一片彩色反而看不出层级
        assertFalse(styles(lines, 2).get(0).fg().isPresent(), String.valueOf(styles(lines, 2)));
    }

    @Test
    @DisplayName("无序列表用 •，嵌套层换标记并落在上一层正文那一列")
    void render_should_indentNestedBulletLists() {
        List<String> lines = texts(render("- 甲\n- 乙\n  - 嵌套\n    - 更深"));

        assertEquals(java.util.Arrays.asList("\u2022 甲", "\u2022 乙", "  \u25e6 嵌套", "    \u25aa 更深"), lines);
    }

    @Test
    @DisplayName("条目续行对齐到标记之后，而不是与标记平齐")
    void render_should_hangListContinuation() {
        // Given：一行太长的条目（宽度 10 → 标记 2 列，正文 8 列，即每行 4 个汉字）
        List<String> lines = texts(MarkdownRenderer.render("- 一二三四五六七八九十", 10, Style.EMPTY));

        // Then：续行前面是 2 列悬挂缩进，正文与标记后的文字对齐
        assertEquals(java.util.Arrays.asList("\u2022 一二三四", "  五六七八", "  九十"), lines);
    }

    @Test
    @DisplayName("有序列表的编号按最大编号右对齐")
    void render_should_alignOrderedListNumbers() {
        // Given：11 项 → 编号占 2 字符
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            source.append(i).append(". 项").append(i).append('\n');
        }

        // When
        List<String> lines = texts(render(source.toString()));

        // Then：个位数编号左侧补空格，与两位数对齐
        assertEquals(11, lines.size());
        assertEquals(" 1. 项1", lines.get(0));
        assertEquals("10. 项10", lines.get(9));
    }

    @Test
    @DisplayName("引用块每行加竖线前缀并降一档亮度，内部块之间保留空行")
    void render_should_prefixQuotesWithBar() {
        // When
        List<VisualLine> lines = render("> 第一段\n>\n> 第二段");

        // Then
        assertEquals(java.util.Arrays.asList("\u2502 第一段", "", "\u2502 第二段"), texts(lines));
        assertTrue(styles(lines, 0).get(0).effectiveModifiers().contains(dev.tamboui.style.Modifier.DIM));
    }

    @Test
    @DisplayName("代码块带围栏与语言标签，内容原样不折行、超宽截断并标记省略")
    void render_should_renderCodeBlockWithoutWrapping() {
        // When：宽度 20，代码行远超
        List<String> lines = texts(MarkdownRenderer.render("```java\nint a = 1;\n```", 20, Style.EMPTY));

        assertEquals(java.util.Arrays.asList("```java", "int a = 1;", "```"), lines);
        List<VisualLine> longCode = MarkdownRenderer.render(
                "```\n" + repeat("x", 50) + "\n```", 20, Style.EMPTY);
        assertTrue(longCode.get(1).text().endsWith(MarkdownRenderer.ELLIPSIS), longCode.get(1).text());
        assertEquals(20, longCode.get(1).width());
    }

    @Test
    @DisplayName("代码块末尾不凭空多出一个空行")
    void render_should_notEmitTrailingBlankLineForCodeBlock() {
        List<String> lines = texts(render("```\na\n```"));

        assertEquals(java.util.Arrays.asList("```", "a", "```"), lines);
    }

    @Test
    @DisplayName("分隔线铺满可用列数")
    void render_should_fillWidthWithThematicBreak() {
        List<VisualLine> lines = MarkdownRenderer.render("---", 12, Style.EMPTY);

        assertEquals(1, lines.size());
        assertEquals(12, lines.get(0).width());
        assertEquals(repeat("\u2500", 12), lines.get(0).text());
    }

    @Test
    @DisplayName("表格降级为代码块：保留行结构但明确不是按列对齐的表格")
    void render_should_degradeTableToCodeBlock() {
        List<String> lines = texts(render("| 甲 | 乙 |\n| --- | --- |\n| 1 | 2 |"));

        assertEquals(java.util.Arrays.asList("```", "| 甲 | 乙 |", "| --- | --- |", "| 1 | 2 |", "```"), lines);
    }

    @Test
    @DisplayName("图片与 HTML 显示源码字面量，不请求也不解释")
    void render_should_showImageAndHtmlAsSource() {
        assertEquals(java.util.Collections.singletonList("![图](http://img)"),
                texts(render("![图](http://img)")));
        assertEquals(java.util.Collections.singletonList("<div>你好</div>"),
                texts(render("<div>你好</div>")));
    }

    @Test
    @DisplayName("链接显示文本并追加地址；自动链接不重复显示地址")
    void render_should_appendLinkDestination() {
        assertEquals(java.util.Collections.singletonList("见文档 (http://x/y)。"),
                texts(render("见[文档](http://x/y)。")));
        assertEquals(java.util.Collections.singletonList("http://x/y"),
                texts(render("<http://x/y>")));
    }

    @Test
    @DisplayName("硬换行保留为真换行，软换行按 CommonMark 折成一个空格")
    void render_should_handleLineBreaks() {
        assertEquals(java.util.Arrays.asList("甲", "乙"), texts(render("甲  \n乙")));
        assertEquals(java.util.Collections.singletonList("甲 乙"), texts(render("甲\n乙")));
    }

    @Test
    @DisplayName("控制字符在解析之前被剔除：模型输出不能改写屏幕")
    void render_should_stripControlCharacters() {
        // Given：ESC 清屏 + 回车 + 双向控制符
        List<String> lines = texts(render("a\u001b[2Jb\rc\u202ed"));

        // Then
        assertFalse(String.join("", lines).contains("\u001b"));
        assertFalse(String.join("", lines).contains("\r"));
        assertFalse(String.join("", lines).contains("\u202e"));
        assertEquals(java.util.Collections.singletonList("a[2Jbcd"), lines);
    }

    @Test
    @DisplayName("未闭合的语法不抛异常，也不影响它前后的块")
    void render_should_tolerateUnclosedSyntax() {
        // Given：未闭合的加粗与未闭合的围栏
        List<String> lines = texts(render("**未闭合\n\n正常段落"));

        // Then：没有异常，后面的段落照常渲染
        assertTrue(lines.contains("正常段落"), String.valueOf(lines));
    }

    @Test
    @DisplayName("病态嵌套不抛异常：深度上限之外只丢样式，不丢文本")
    void render_should_survivePathologicalNesting() {
        // Given：200 层引用 + 100 层强调
        StringBuilder quotes = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            quotes.append("> ");
        }
        String emphasis = repeat("*", 100) + "内容" + repeat("*", 100);

        // Then：不抛异常，内容仍然可见（竖线前缀的深度被钳住，文字没被挤成每行一字）
        List<String> lines = texts(render(quotes.append(emphasis).toString()));
        assertFalse(lines.isEmpty());
        // 折行可能正好落在「内」「容」之间，而前缀段会插在两者中间，因此先把前缀去掉再找
        String joined = String.join("", lines).replace("\u2502", "").replace(" ", "");
        assertTrue(joined.contains("内容"), joined);
    }

    @Test
    @DisplayName("中文与 emoji 都不越界：每行显示宽度不超过可用列数")
    void render_should_neverExceedWidth() {
        // Given：一段混排内容，含 CJK、emoji 代理对、长 URL、代码块
        String source = "# 中文标题\n\n一段很长的中文说明文字，用来验证换行是否按列数而不是按字符数计算 🐟🐟🐟。\n\n"
                + "- 列表项里也有一大段中文，包含一个很长的地址 http://example.com/very/long/path/segments\n\n"
                + "```\n" + repeat("中文abc", 20) + "\n```";

        for (int width : new int[]{8, 16, 40}) {
            // When
            List<VisualLine> lines = render(source, width);

            // Then
            for (VisualLine line : lines) {
                assertTrue(line.width() <= width, "宽 " + width + " 下越界：" + line.text());
            }
        }
    }

    @Test
    @DisplayName("超长文本的头部退回纯文本，尾部仍按 markdown 渲染")
    void render_should_flatRenderHeadOfOversizedText() {
        // Given：头部恰好是一条 markdown 标题，其余用普通字符把总长顶过解析上限
        String source = "# 头标题\n" + repeat("x", MarkdownRenderer.PARSE_MAX_CHARS)
                + "\n\n## 尾标题\n";

        // When
        List<VisualLine> lines = render(source);

        // Then：头部按原文铺出（标记没有被解析掉），尾部仍是标题（带块前缀）
        assertEquals("# 头标题", lines.get(0).text());
        assertTrue(lines.get(lines.size() - 1).text().startsWith("\u258c 尾标题"),
                lines.get(lines.size() - 1).text());
    }

    @Test
    @DisplayName("恰好不越上限时不做退化：整段都按 markdown 渲染")
    void render_should_notDegradeWhenWithinParseLimit() {
        // Given：总长正好等于解析上限的文本，开头是一条标题
        String source = "# 头标题\n" + repeat("x", MarkdownRenderer.PARSE_MAX_CHARS - 8);

        // When
        List<VisualLine> lines = render(source);

        // Then：标题仍被解析（退化路径没有生效）
        assertEquals("\u258c 头标题", lines.get(0).text());
    }

    /**
     * 渲染一段 markdown。
     *
     * @param source markdown 原文
     * @return 视觉行
     */
    private static List<VisualLine> render(String source) {
        return render(source, WIDTH);
    }

    /**
     * 按指定宽度渲染一段 markdown。
     *
     * @param source markdown 原文
     * @param width  可用列数
     * @return 视觉行
     */
    private static List<VisualLine> render(String source, int width) {
        return MarkdownRenderer.render(source, width, Style.EMPTY);
    }

    /**
     * 取视觉行的纯文本。
     *
     * @param lines 视觉行
     * @return 文本列表
     */
    private static List<String> texts(List<VisualLine> lines) {
        List<String> result = new ArrayList<String>(lines.size());
        for (VisualLine line : lines) {
            result.add(line.text());
        }
        return result;
    }

    /**
     * 取指定视觉行里各文本段的样式。
     *
     * @param lines 视觉行
     * @param index 行下标
     * @return 样式列表（跳过空前缀段）
     */
    private static List<Style> styles(List<VisualLine> lines, int index) {
        List<Style> result = new ArrayList<Style>();
        for (StyledSegment segment : lines.get(index).getSegments()) {
            if (!segment.isEmpty()) {
                result.add(segment.getStyle());
            }
        }
        return result;
    }

    /**
     * 重复一个字符串。
     *
     * @param unit  重复单元
     * @param times 次数
     * @return 重复结果
     */
    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
