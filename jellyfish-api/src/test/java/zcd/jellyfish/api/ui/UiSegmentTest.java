package zcd.jellyfish.api.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiSegment} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("界面文本段")
class UiSegmentTest {

    @Test
    @DisplayName("默认档位是常规，避免插件被迫为普通文本写一个枚举值")
    void of_should_defaultToNormal() {
        assertEquals(UiEmphasis.NORMAL, UiSegment.of("正文").getEmphasis());
    }

    @Test
    @DisplayName("null 档位按常规处理：外壳不该因为一个 null 就整块不显示")
    void constructor_should_fallBackToNormal_when_emphasisIsNull() {
        assertEquals(UiEmphasis.NORMAL, UiSegment.of("正文", null).getEmphasis());
    }

    @Test
    @DisplayName("文本与档位原样保留")
    void getters_should_returnConstructorValues() {
        UiSegment segment = UiSegment.of("12/40", UiEmphasis.ACCENT);
        assertEquals("12/40", segment.getText());
        assertEquals(UiEmphasis.ACCENT, segment.getEmphasis());
    }

    @Test
    @DisplayName("空文本段保留而不是归一为空：段是样式边界，外壳折行时还要用它")
    void isEmpty_should_beTrue_when_textIsEmpty() {
        assertTrue(UiSegment.of("").isEmpty());
        assertFalse(UiSegment.of(" ").isEmpty());
    }

    @Test
    @DisplayName("文本为 null 直接拒绝：静默当成空串会掩盖插件的参数错误")
    void constructor_should_rejectNullText() {
        assertThrows(NullPointerException.class, () -> UiSegment.of(null));
    }
}
