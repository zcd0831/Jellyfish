package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputReferenceEscapes} 的单元测试：转义/还原与「这个空白是不是边界」。
 * <p>
 * 这一层的判据同时被三处用着（插件造插入文本、插件还原路径、外壳切片段），
 * 因此它必须<b>可逆且自洽</b>：{@code unescape(escape(x)) == x} 是这类约定的底线，
 * 否则会出现「补全插进去的路径，插件自己认不出来」这种两头都对不上的现场。
 *
 * @author zcd
 */
@DisplayName("行内引用转义")
class InputReferenceEscapesTest {

    @Test
    @DisplayName("含空格的路径应转义成 \\ 加空格")
    void escape_should_escapeWhitespace() {
        assertEquals("my\\ file.txt", InputReferenceEscapes.escape("my file.txt"));
    }

    @Test
    @DisplayName("反斜杠本身也要转义，否则还原时说不清哪个是真的")
    void escape_should_escapeBackslash() {
        assertEquals("a\\\\b", InputReferenceEscapes.escape("a\\b"));
        assertEquals("a\\\\\\ b", InputReferenceEscapes.escape("a\\ b"));
    }

    @Test
    @DisplayName("转义再还原必须回到原文")
    void unescape_should_invertEscape() {
        for (String path : new String[]{"my file.txt", "a\\b", "a\\ b", "普通路径/无空格.txt", ""}) {
            assertEquals(path, InputReferenceEscapes.unescape(InputReferenceEscapes.escape(path)), path);
        }
    }

    @Test
    @DisplayName("还原遇到不认识的转义应原样保留：用户手敲的 Windows 路径不该被弄坏")
    void unescape_should_keepUnknownEscapes() {
        assertEquals("C:\\tmp\\a.txt", InputReferenceEscapes.unescape("C:\\tmp\\a.txt"));
    }

    @Test
    @DisplayName("null 原样返回，不做特殊处理")
    void unescape_should_returnNull_when_null() {
        assertEquals(null, InputReferenceEscapes.unescape(null));
        assertEquals(null, InputReferenceEscapes.escape(null));
    }

    @Test
    @DisplayName("转义过的空白不是边界，两个反斜杠之后的空白才是")
    void isEscapedAt_should_countBackslashes() {
        String single = "@my\\ file.txt";
        assertTrue(InputReferenceEscapes.isEscapedAt(single, single.indexOf(' ')));

        String doubled = "@a\\\\ b";
        assertFalse(InputReferenceEscapes.isEscapedAt(doubled, doubled.indexOf(' ')));

        // 行首的空白与越界下标都不算「被转义」，避免调用方还要各自判边界
        assertFalse(InputReferenceEscapes.isEscapedAt(" x", 0));
        assertFalse(InputReferenceEscapes.isEscapedAt("x", 9));
        assertFalse(InputReferenceEscapes.isEscapedAt(null, 1));
    }
}
