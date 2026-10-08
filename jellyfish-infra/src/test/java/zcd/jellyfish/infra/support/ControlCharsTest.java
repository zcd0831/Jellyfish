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
    @DisplayName("单行化：换行换成空格，控制字符照旧剔除")
    void singleLine_should_replaceNewlineAndStripControls() {
        // 日志行与 CLI 诊断行都要求单行——一段带 \n 的输入能在那里伪造出整行
        assertEquals("a b", ControlChars.singleLine("a\nb"));
        // 注意剔除的是 ESC 本身，剩下的 `[2J` 只是三个普通字符：引导符没了就不再是控制序列
        assertEquals("a[2Jb", ControlChars.singleLine("a\u001b[2Jb"));
        assertEquals("会话不存在[2J 已授权",
                ControlChars.singleLine("会话不存在\u001b[2J\n已授权"));
    }

    @Test
    @DisplayName("单行化保留制表符以外的排版字符：制表符仍按 strip 的规则换成空格")
    void singleLine_should_softenTabLikeStrip() {
        assertEquals("a b", ControlChars.singleLine("a\tb"));
    }

    @Test
    @DisplayName("单行化：干净的单行文本原样返回，CJK 与 emoji 不受影响")
    void singleLine_should_keepPlainText() {
        assertEquals("中文 🐟 完成", ControlChars.singleLine("中文 🐟 完成"));
    }

    @Test
    @DisplayName("单行化：空值与空串原样返回")
    void singleLine_should_passThroughNullAndEmpty() {
        assertNull(ControlChars.singleLine(null));
        assertEquals("", ControlChars.singleLine(""));
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
