package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LineWrapper} 的单元测试。
 * <p>
 * 关注点是「视觉行数与每行内容」——滚动按视觉行计数，换行错一行，滚动位置就与屏幕内容错一行。
 *
 * @author zcd
 */
class LineWrapperTest {

    /** 测试用前缀（宽度 4）。 */
    private static final StyledSegment PREFIX = new StyledSegment("  \u276f ", Style.EMPTY);

    @Test
    @DisplayName("正文放得下时不换行，输出单行且带前缀")
    void wrap_should_emit_single_line_when_body_fits() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("hello")), 20);

        assertEquals(1, lines.size());
        assertEquals("  \u276f hello", lines.get(0).text());
    }

    @Test
    @DisplayName("正文超出宽度时换行，续行用等宽空白缩进")
    void wrap_should_indent_continuation_lines_when_body_overflows() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("abcdefghij")), 9);

        // 前缀占 4 列，正文每行可用 5 列 → 10 个字符正好两行
        assertEquals(2, lines.size());
        assertEquals("  \u276f abcde", lines.get(0).text());
        assertEquals("    fghij", lines.get(1).text());
    }

    @Test
    @DisplayName("中文按 2 列计算，不会被挤出行宽")
    void wrap_should_respect_display_width_when_cjk() {
        // 前缀 4 列，总宽 10 → 正文每行 6 列 = 3 个汉字
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("一二三四五六")), 10);

        assertEquals(2, lines.size());
        assertEquals("  \u276f 一二三", lines.get(0).text());
        assertEquals("    四五六", lines.get(1).text());
        for (VisualLine line : lines) {
            assertTrue(line.width() <= 10, "每行不得超过可用列数");
        }
    }

    @Test
    @DisplayName("正文里的换行符触发强制换行")
    void wrap_should_break_when_explicit_newline() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("a\nb")), 40);

        assertEquals(2, lines.size());
        assertEquals("  \u276f a", lines.get(0).text());
        assertEquals("    b", lines.get(1).text());
    }

    @Test
    @DisplayName("续行行首的空格被丢弃，避免锯齿缩进")
    void wrap_should_drop_leading_space_on_continuation() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("abcde fghij")), 9);

        assertEquals(2, lines.size());
        assertEquals("  \u276f abcde", lines.get(0).text());
        assertEquals("    fghij", lines.get(1).text());
    }

    @Test
    @DisplayName("正文为空时仍输出一行：只有前缀")
    void wrap_should_emit_prefix_only_line_when_body_empty() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX, Collections.<StyledSegment>emptyList(), 20);

        assertEquals(1, lines.size());
        assertEquals("  \u276f ", lines.get(0).text());
    }

    @Test
    @DisplayName("相邻同样式码点合并成一个样式段")
    void wrap_should_merge_adjacent_same_style_segments() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX, Arrays.asList(
                StyledSegment.of("ab"),
                StyledSegment.of("cd")), 40);

        // 前缀一段 + 正文合并成一段
        assertEquals(2, lines.get(0).getSegments().size());
        assertEquals("abcd", lines.get(0).getSegments().get(1).getText());
    }

    @Test
    @DisplayName("样式切换处保持为独立样式段")
    void wrap_should_keep_separate_segments_when_style_changes() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX, Arrays.asList(
                StyledSegment.of("ab"),
                new StyledSegment("cd", Style.EMPTY.dim())), 40);

        List<StyledSegment> segments = lines.get(0).getSegments();
        assertEquals(3, segments.size());
        assertEquals("ab", segments.get(1).getText());
        assertEquals("cd", segments.get(2).getText());
        assertEquals(Style.EMPTY, segments.get(1).getStyle());
        assertFalse(Style.EMPTY.equals(segments.get(2).getStyle()));
    }

    @Test
    @DisplayName("宽度退化到 1 时不抛异常；前缀可能溢出行宽，这是刻意接受的版式崩坏")
    void wrap_should_not_throw_when_width_degenerate() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("ab")), 1);

        // 极窄终端下不保证排版，但必须不抛异常、不进入死循环，且正文仍然每行一列
        assertFalse(lines.isEmpty());
        for (VisualLine line : lines) {
            assertFalse(line.text().isEmpty());
        }
    }

    @Test
    @DisplayName("前缀比可用宽度还宽时，正文仍按至少一列切开")
    void wrap_should_keep_body_lines_when_prefix_wider_than_width() {
        List<VisualLine> lines = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("abc")), 3);

        assertEquals(3, lines.size(), "正文每个字符单独成行");
    }

    @Test
    @DisplayName("纯文本重载与样式版结果一致")
    void wrap_should_match_styled_overload_when_plain_text() {
        List<VisualLine> plain = LineWrapper.wrap("  \u276f ", "hello", 20);
        List<VisualLine> styled = LineWrapper.wrap(PREFIX,
                Collections.singletonList(StyledSegment.of("hello")), 20);

        assertEquals(styled.get(0).text(), plain.get(0).text());
    }
}
