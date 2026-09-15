package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatusLineContribution} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("状态栏贡献结果")
class StatusLineContributionTest {

    @Test
    @DisplayName("有内容时原样保留文本")
    void of_should_keepText() {
        assertEquals("待办 2/5", StatusLineContribution.of("待办 2/5").getText());
    }

    @Test
    @DisplayName("首尾空白会被裁掉：状态栏每一列都很贵")
    void of_should_trimText() {
        assertEquals("待办 2/5", StatusLineContribution.of("  待办 2/5  ").getText());
    }

    @Test
    @DisplayName("null 归一为空贡献")
    void of_should_returnEmpty_when_textIsNull() {
        assertTrue(StatusLineContribution.of(null).isEmpty());
    }

    @Test
    @DisplayName("空白文本归一为空贡献：否则拼接处会多出双倍间隔")
    void of_should_returnEmpty_when_textIsBlank() {
        assertTrue(StatusLineContribution.of("   ").isEmpty());
    }

    @Test
    @DisplayName("空贡献是同一个实例且没有文本")
    void empty_should_haveNoText() {
        assertSame(StatusLineContribution.empty(), StatusLineContribution.of(null));
        assertNull(StatusLineContribution.empty().getText());
        assertTrue(StatusLineContribution.empty().isEmpty());
    }

    @Test
    @DisplayName("有内容的贡献不是空贡献")
    void isEmpty_should_beFalse_when_textPresent() {
        assertFalse(StatusLineContribution.of("x").isEmpty());
    }
}
