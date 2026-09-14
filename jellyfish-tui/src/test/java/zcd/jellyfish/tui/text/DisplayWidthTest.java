package zcd.jellyfish.tui.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DisplayWidth} 的单元测试。
 * <p>
 * 关注点是「终端列数」而非「UTF-16 长度」——中文换行、缩进对齐全靠这个函数，
 * 它算错一个码点，屏幕上就会错一列。
 *
 * @author zcd
 */
class DisplayWidthTest {

    @ParameterizedTest
    @CsvSource({
            "'', 0",
            "a, 1",
            "abc, 3",
            "' ', 1"
    })
    @DisplayName("窄字符按 1 列计算")
    void of_should_count_one_column_when_ascii(String text, int expected) {
        assertEquals(expected, DisplayWidth.of(text));
    }

    @ParameterizedTest
    @CsvSource({
            "你好, 4",
            "中a, 3",
            "日本語, 6"
    })
    @DisplayName("CJK 与全角字符按 2 列计算")
    void of_should_count_two_columns_when_wide_character(String text, int expected) {
        assertEquals(expected, DisplayWidth.of(text));
    }

    @Test
    @DisplayName("全角标点按 2 列计算")
    void of_should_count_two_columns_when_fullwidth_punctuation() {
        // 全角（）、，各占 2 列，合计 6
        assertEquals(6, DisplayWidth.of("\uff08\uff09\uff0c"));
    }

    @ParameterizedTest
    @CsvSource({
            "한글, 4",
            "한a, 3"
    })
    @DisplayName("Hangul 音节按 2 列计算")
    void of_should_count_two_columns_when_hangul(String text, int expected) {
        assertEquals(expected, DisplayWidth.of(text));
    }

    @ParameterizedTest
    @CsvSource({
            "e\u0301, 1",
            "a\u0300b, 2"
    })
    @DisplayName("组合附加符号不占列")
    void of_should_ignore_combining_marks(String text, int expected) {
        assertEquals(expected, DisplayWidth.of(text));
    }

    @ParameterizedTest
    @CsvSource({
            "0xFEFF, 0",
            "0x200B, 0",
            "0xFE0F, 0",
            "0x41, 1",
            "0x4E2D, 2",
            "0x1F600, 2"
    })
    @DisplayName("单码点宽度判定")
    void ofCodePoint_should_return_expected_width(int codePoint, int expected) {
        assertEquals(expected, DisplayWidth.ofCodePoint(codePoint));
    }

    @ParameterizedTest
    @CsvSource({
            "0xFEFF, true",
            "0x0301, true",
            "0x41, false",
            "0x4E2D, false"
    })
    @DisplayName("零宽判定")
    void isZeroWidth_should_detect_zero_width_codepoints(int codePoint, boolean expected) {
        assertEquals(expected, DisplayWidth.isZeroWidth(codePoint));
    }

    @ParameterizedTest
    @CsvSource({
            "0x4E2D, true",
            "0xFF08, true",
            "0xAC00, true",
            "0x20000, true",
            "0x276F, false",
            "0x23BF, false",
            "0x41, false"
    })
    @DisplayName("宽字符判定：本项目用到的行首符号必须仍是 1 列，否则前缀宽度会算错")
    void isWide_should_detect_wide_codepoints(int codePoint, boolean expected) {
        assertEquals(expected, DisplayWidth.isWide(codePoint));
    }

    @ParameterizedTest
    @CsvSource({
            "'  \u276f ', 4",
            "'      \u23bf ', 8",
            "'  \u23fa jellyfish', 13"
    })
    @DisplayName("投影器实际使用的前缀宽度必须与预期一致")
    void of_should_measure_projector_prefixes(String prefix, int expected) {
        assertEquals(expected, DisplayWidth.of(prefix));
    }
}
