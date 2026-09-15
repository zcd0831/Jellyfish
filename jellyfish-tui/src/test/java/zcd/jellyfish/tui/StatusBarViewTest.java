package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatusBarView} 中「插件片段拼接」的单元测试。
 * <p>
 * 只测这条纯函数：它决定插件片段在哪一列被丢掉，而丢错的表现是「状态栏被撑爆」或
 * 「明明有内容却不显示」，两种都很难在真机上定位。
 *
 * @author zcd
 */
@DisplayName("状态栏插件片段拼接")
class StatusBarViewTest {

    /** 片段分隔符，与实现保持一致。 */
    private static final String GAP = "   ";

    @Test
    @DisplayName("没有片段时原样返回内核字段")
    void appendFragments_should_returnBase_when_noFragments() {
        assertEquals("agent \u00b7 model", StatusBarView.appendFragments("agent \u00b7 model", null, 80));
        assertEquals("agent \u00b7 model", StatusBarView.appendFragments("agent \u00b7 model",
                Collections.<String>emptyList(), 80));
    }

    @Test
    @DisplayName("多个片段按传入顺序依次拼接")
    void appendFragments_should_joinInOrder() {
        assertEquals("base" + GAP + "a" + GAP + "b",
                StatusBarView.appendFragments("base", Arrays.asList("a", "b"), 80));
    }

    @Test
    @DisplayName("宽度不够时从最后一个片段起整块丢弃，而不是截断半个")
    void appendFragments_should_dropTrailingFragments_when_widthExceeded() {
        // "base" 宽 4；"base   aa" 宽 9；再加上 "   bb" 就成了 14
        assertEquals("base" + GAP + "aa",
                StatusBarView.appendFragments("base", Arrays.asList("aa", "bb"), 9));
    }

    @Test
    @DisplayName("连第一个片段都装不下时保留内核字段本身")
    void appendFragments_should_keepBase_when_firstFragmentTooWide() {
        assertEquals("base", StatusBarView.appendFragments("base", Collections.singletonList("very-long"), 5));
    }

    @Test
    @DisplayName("null 与空片段被跳过，不留下多余分隔符")
    void appendFragments_should_ignoreBlankFragments() {
        List<String> fragments = Arrays.asList(null, "", "a");

        assertEquals("base" + GAP + "a", StatusBarView.appendFragments("base", fragments, 80));
    }

    @Test
    @DisplayName("按显示列数而非字符数限宽：中文片段按两列算")
    void appendFragments_should_countWideCharactersAsTwoColumns() {
        // "待办 2/5" 只有 6 个字符，但显示宽度是 8；加上分隔符共 11 列
        String fragment = "待办 2/5";
        assertEquals(6, fragment.length());
        assertEquals("", StatusBarView.appendFragments("", Collections.singletonList(fragment), 9));
        assertEquals(GAP + fragment, StatusBarView.appendFragments("", Collections.singletonList(fragment), 11));
    }

    @Test
    @DisplayName("宽度不是正数时不限宽：留给「不知道终端宽度」的调用方")
    void appendFragments_should_notLimit_when_terminalWidthNotPositive() {
        String expected = "base" + GAP + "long-fragment";

        assertEquals(expected, StatusBarView.appendFragments("base",
                Collections.singletonList("long-fragment"), 0));
        assertEquals(expected, StatusBarView.appendFragments("base",
                Collections.singletonList("long-fragment"), -1));
    }

    @Test
    @DisplayName("基座为 null 时也能拼接，不抛空指针")
    void appendFragments_should_tolerateNullBase() {
        assertEquals(GAP + "a", StatusBarView.appendFragments(null, Collections.singletonList("a"), 80));
    }
}
