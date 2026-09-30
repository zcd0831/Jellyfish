package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ControlChars} 的单元测试：钉住「不可信文本不能改写终端」这条安全前提。
 *
 * @author zcd
 */
@DisplayName("ControlChars 控制字符过滤")
class ControlCharsTest {

    @Test
    @DisplayName("ESC 与控制序列引导符被剔除：一段参数不能清屏或挪光标")
    void strip_should_removeEscape() {
        // When
        String stripped = ControlChars.strip("a\u001b[2Jb\u001b[Hc");

        // Then
        assertEquals("a[2Jb[Hc", stripped);
        assertFalse(stripped.contains("\u001b"));
    }

    @Test
    @DisplayName("回车与退格被剔除：它们能原地改写已显示的一行")
    void strip_should_removeCarriageReturnAndBackspace() {
        assertEquals("abc", ControlChars.strip("a\rb\bc"));
    }

    @Test
    @DisplayName("双向控制符被剔除：它们能让文本视觉上读作相反顺序")
    void strip_should_removeBidiControls() {
        assertEquals("ab", ControlChars.strip("a\u202eb\u2066"));
    }

    @Test
    @DisplayName("换行保留，制表符换成空格而不是删除")
    void strip_should_keepNewlineAndSoftenTab() {
        assertEquals("a\nb c", ControlChars.strip("a\nb\tc"));
    }

    @Test
    @DisplayName("C1 控制字符与 DEL 也被剔除")
    void strip_should_removeC1AndDel() {
        assertEquals("ab", ControlChars.strip("a\u0085\u007fb"));
    }

    @Test
    @DisplayName("普通文本原样返回，包括 CJK 与 emoji")
    void strip_should_keepPlainText() {
        assertEquals("中文 🐟 ok", ControlChars.strip("中文 🐟 ok"));
    }

    @Test
    @DisplayName("空值与空串原样返回，不做多余分配")
    void strip_should_passThroughNullAndEmpty() {
        assertNull(ControlChars.strip(null));
        assertEquals("", ControlChars.strip(""));
    }

    @Test
    @DisplayName("过滤后不残留任何控制字符")
    void strip_should_leaveNoControlCharacters() {
        // Given：一串混了各种控制字符的文本
        String dirty = "\u0001a\u0007b\u001bc\u0085d\re\ff\tg\nh";

        // When
        String stripped = ControlChars.strip(dirty);

        // Then
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            assertTrue(c == '\n' || Character.getType(c) != Character.CONTROL,
                    "残留控制字符：" + (int) c);
        }
    }
}
