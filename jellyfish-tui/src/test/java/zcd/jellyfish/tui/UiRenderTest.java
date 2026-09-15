package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiRender} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("插件界面内容渲染")
class UiRenderTest {

    @Test
    @DisplayName("常规档位就是无样式：正文不该被外壳擅自加装饰")
    void emphasisStyle_should_beEmptyForNormal() {
        assertEquals(Style.EMPTY, UiRender.emphasisStyle(UiEmphasis.NORMAL));
    }

    @Test
    @DisplayName("null 档位按常规处理：插件漏填不该变成另一种观感")
    void emphasisStyle_should_fallBackToNormal_when_null() {
        assertEquals(UiRender.emphasisStyle(UiEmphasis.NORMAL), UiRender.emphasisStyle(null));
    }

    @Test
    @DisplayName("四个非寻常档位都带样式，且五个档位互不相同")
    void emphasisStyle_should_differPerEmphasis() {
        Style normal = UiRender.emphasisStyle(UiEmphasis.NORMAL);
        List<Style> styles = new ArrayList<Style>();
        for (UiEmphasis emphasis : UiEmphasis.values()) {
            Style style = UiRender.emphasisStyle(emphasis);
            if (emphasis != UiEmphasis.NORMAL) {
                assertNotEquals(normal, style, "档位 " + emphasis + " 没有可区分的样式");
            }
            styles.add(style);
        }
        for (int i = 0; i < styles.size(); i++) {
            for (int j = i + 1; j < styles.size(); j++) {
                assertNotEquals(styles.get(i), styles.get(j),
                        "档位 " + UiEmphasis.values()[i] + " 与 " + UiEmphasis.values()[j] + " 映射成了同一种样式");
            }
        }
    }

    @Test
    @DisplayName("内容宽度按显示宽度算：全中文面板不能被按字符数算窄一半")
    void contentWidth_should_countDisplayWidth() {
        PanelContribution contribution = contributionOf(UiLine.of("中文四个字"));

        assertEquals(10, UiRender.contentWidth(contribution));
    }

    @Test
    @DisplayName("内容宽度取最长一行；空贡献为 0")
    void contentWidth_should_takeMax() {
        PanelContribution contribution = contributionOf(UiLine.of("短"), UiLine.of("长一点点"));

        // 「长一点点」是 4 个汉字 = 8 列，按字符数算只有 4
        assertEquals(8, UiRender.contentWidth(contribution));
        assertEquals(0, UiRender.contentWidth(null));
        assertEquals(0, UiRender.contentWidth(PanelContribution.empty()));
    }

    @Test
    @DisplayName("内容行数就是逻辑行数（不含边框）")
    void contentRows_should_countLines() {
        assertEquals(2, UiRender.contentRows(contributionOf(UiLine.of("a"), UiLine.of("b"))));
        assertEquals(0, UiRender.contentRows(null));
    }

    @Test
    @DisplayName("超宽行按显示宽度折行，各段保留自己的样式档位")
    void toVisualLines_should_wrapByDisplayWidth() {
        PanelContribution contribution = contributionOf(
                UiLine.of(UiSegment.of("中文"), UiSegment.of("abc", UiEmphasis.ERROR)));

        List<VisualLine> lines = UiRender.toVisualLines(contribution, 4, 10);

        assertEquals(2, lines.size());
        assertEquals("中文", lines.get(0).text());
        assertEquals("abc", lines.get(1).text());
        // 首段是折行器插入的前缀段（空前缀），正文段在它之后
        assertTrue(lines.get(1).getSegments().stream()
                        .anyMatch(segment -> UiRender.emphasisStyle(UiEmphasis.ERROR).equals(segment.getStyle())),
                "续行正文应保留插件的 ERROR 档位");
    }

    @Test
    @DisplayName("行数超限时截断并留一行提示：直接砍掉会让人以为面板本来就是完整的")
    void toVisualLines_should_truncateWithHint() {
        List<UiLine> content = new ArrayList<UiLine>();
        for (int i = 0; i < 8; i++) {
            content.add(UiLine.of("第 " + i + " 行"));
        }
        PanelContribution contribution = PanelContribution.of("标题", content, UiRegion.DOCK);

        List<VisualLine> lines = UiRender.toVisualLines(contribution, 40, 3);

        assertEquals(3, lines.size());
        assertEquals("第 0 行", lines.get(0).text());
        assertEquals("第 1 行", lines.get(1).text());
        assertTrue(lines.get(2).text().contains("6"), "提示行应说明还有多少行没显示：" + lines.get(2).text());
    }

    @Test
    @DisplayName("刚好不超限时不加提示行")
    void toVisualLines_should_notAddHint_when_fits() {
        PanelContribution contribution = contributionOf(UiLine.of("a"), UiLine.of("b"));

        assertEquals(2, UiRender.toVisualLines(contribution, 40, 2).size());
    }

    @Test
    @DisplayName("空行保留为视觉空行：面板里的空行是排版的一部分")
    void toVisualLines_should_keepBlankLine() {
        PanelContribution contribution = contributionOf(UiLine.of("a"), UiLine.EMPTY, UiLine.of("b"));

        List<VisualLine> lines = UiRender.toVisualLines(contribution, 40, 10);

        assertEquals(3, lines.size());
        assertEquals("", lines.get(1).text());
    }

    @Test
    @DisplayName("空贡献折出零行：外壳据此不占区域")
    void toVisualLines_should_returnEmpty_when_noContent() {
        assertTrue(UiRender.toVisualLines(null, 40, 10).isEmpty());
        assertTrue(UiRender.toVisualLines(PanelContribution.empty(), 40, 10).isEmpty());
    }

    @Test
    @DisplayName("行数上限小于 1 时按 1 处理：不产生负数量或空结果")
    void toVisualLines_should_treatNonPositiveLimitAsOne() {
        List<VisualLine> lines = UiRender.toVisualLines(contributionOf(UiLine.of("a"), UiLine.of("b")), 40, 0);

        assertEquals(1, lines.size());
        assertSame(VisualLine.class, lines.get(0).getClass());
    }

    /**
     * 构造一个无标题、无建议区域的面板。
     *
     * @param lines 内容行
     * @return 面板
     */
    private static PanelContribution contributionOf(UiLine... lines) {
        return PanelContribution.of(null, Collections.unmodifiableList(java.util.Arrays.asList(lines)), null);
    }
}
