package zcd.jellyfish.api.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiLine} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("界面逻辑行")
class UiLineTest {

    @Test
    @DisplayName("纯文本行只有一个常规段")
    void of_should_buildSingleNormalSegment() {
        UiLine line = UiLine.of("已索引 12 个文件");
        assertEquals("已索引 12 个文件", line.text());
        assertEquals(1, line.getSegments().size());
        assertEquals(UiEmphasis.NORMAL, line.getSegments().get(0).getEmphasis());
    }

    @Test
    @DisplayName("多段按顺序拼接，各自保留自己的档位")
    void of_should_keepSegmentOrderAndEmphasis() {
        UiLine line = UiLine.of(UiSegment.of("已索引 "),
                UiSegment.of("12/40", UiEmphasis.ACCENT),
                UiSegment.of(" 个文件"));
        assertEquals("已索引 12/40 个文件", line.text());
        assertEquals(3, line.getSegments().size());
        assertEquals(UiEmphasis.ACCENT, line.getSegments().get(1).getEmphasis());
    }

    @Test
    @DisplayName("空文本段被丢弃：留下只会让外壳多一次无意义的样式切换")
    void constructor_should_dropEmptySegments() {
        UiLine line = UiLine.of(UiSegment.of(""), UiSegment.of("正文"), UiSegment.of(""));
        assertEquals(1, line.getSegments().size());
        assertEquals("正文", line.text());
    }

    @Test
    @DisplayName("全为空段时等价于空行")
    void constructor_should_becomeEmpty_when_allSegmentsEmpty() {
        assertTrue(UiLine.of(UiSegment.of("")).isEmpty());
        assertTrue(UiLine.of((UiSegment[]) null).isEmpty());
    }

    @Test
    @DisplayName("null 与空文本都归一为空行")
    void of_should_returnEmpty_when_textIsNullOrEmpty() {
        assertSame(UiLine.EMPTY, UiLine.of((String) null));
        assertSame(UiLine.EMPTY, UiLine.of(""));
        assertTrue(UiLine.EMPTY.isEmpty());
    }

    @Test
    @DisplayName("段列表不可变：快照会被渲染线程反复读取，不该能被改动")
    void getSegments_should_beUnmodifiable() {
        List<UiSegment> source = new ArrayList<UiSegment>();
        source.add(UiSegment.of("a"));
        UiLine line = new UiLine(source);
        source.add(UiSegment.of("b"));
        assertEquals("a", line.text());
        assertThrows(UnsupportedOperationException.class,
                () -> line.getSegments().add(UiSegment.of("c")));
    }

    @Test
    @DisplayName("有内容的行不是空行")
    void isEmpty_should_beFalse_when_segmentPresent() {
        assertFalse(new UiLine(Arrays.asList(UiSegment.of("x"))).isEmpty());
    }
}
