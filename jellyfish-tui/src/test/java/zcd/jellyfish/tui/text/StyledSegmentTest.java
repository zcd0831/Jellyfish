package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StyledSegment} 的单元测试：取值语义与<b>控制字符收口</b>。
 * <p>
 * 收口这件事值得单独立几条用例：它是所有屏幕文本的必经之路，漏了它，面板、状态栏、工具名、
 * 文件名候选就各自回到「谁记得滤谁滤」的状态。
 *
 * @author zcd
 */
@DisplayName("带样式文本段")
class StyledSegmentTest {

    @Test
    @DisplayName("构造时滤掉 ESC：一个转义序列能清屏或把光标挪回去覆盖界面")
    void constructor_should_stripEscapeSequence() {
        // When：清屏序列与光标归位序列
        StyledSegment segment = new StyledSegment("a\u001b[2Jb\u001b[Hc", Style.EMPTY);

        // Then：引导符没了，剩下的方括号内容只是普通可见字符
        assertEquals("a[2Jb[Hc", segment.getText());
        assertFalse(segment.getText().indexOf('\u001b') >= 0);
    }

    @Test
    @DisplayName("构造时滤掉回车与退格：它们能把已显示的一行原地改写")
    void constructor_should_stripCarriageReturnAndBackspace() {
        assertEquals("abc", new StyledSegment("a\rb\bc", Style.EMPTY).getText());
    }

    @Test
    @DisplayName("换行与制表符的处理与既有口径一致：换行留着，制表符变空格")
    void constructor_should_keepNewlineAndExpandTab() {
        // 换行是排版语义（外层自己掌控），制表符删掉会让 a\tb 粘成 ab 而改变内容含义
        assertEquals("a\nb c", new StyledSegment("a\nb\tc", Style.EMPTY).getText());
    }

    @Test
    @DisplayName("干净文本原样保留：这是绝大多数情况，也是过滤必须幂等的原因")
    void constructor_should_keepCleanText() {
        assertEquals("中文 🐟 ok", new StyledSegment("中文 🐟 ok", Style.EMPTY).getText());
    }

    @Test
    @DisplayName("空段与静态工厂都不受影响")
    void emptyAndFactory_should_behaveAsBefore() {
        assertTrue(StyledSegment.EMPTY.isEmpty());
        assertEquals("x", StyledSegment.of("x").getText());
    }

    @Test
    @DisplayName("显示宽度按滤过之后的文本算：控制字符不占列数")
    void width_should_ignoreStrippedControlChars() {
        StyledSegment segment = new StyledSegment("ab\u001b[2J", Style.EMPTY);

        assertEquals(5, segment.width());
    }
}
