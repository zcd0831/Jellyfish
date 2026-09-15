package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.api.ui.UiEmphasis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PanelContribution} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("面板贡献结果")
class PanelContributionTest {

    @Test
    @DisplayName("标题与建议区域都可以为空：都是外壳可以忽略的软信息")
    void of_should_allowNullTitleAndRegion() {
        PanelContribution contribution =
                PanelContribution.of(null, Arrays.asList(UiLine.of("正文")), null);
        assertNull(contribution.getTitle());
        assertNull(contribution.getPreferredRegion());
        assertEquals(1, contribution.getLines().size());
    }

    @Test
    @DisplayName("建议区域原样保留：外壳可以忽略，但不能替插件改写")
    void of_should_keepPreferredRegion() {
        PanelContribution contribution =
                PanelContribution.of("索引", Arrays.asList(UiLine.of("正文")), UiRegion.LEFT);
        assertEquals(UiRegion.LEFT, contribution.getPreferredRegion());
    }

    @Test
    @DisplayName("没有内容行时归一为空贡献：外壳据此不占区域")
    void of_should_returnEmpty_when_linesMissing() {
        assertSame(PanelContribution.empty(), PanelContribution.of("标题", null, UiRegion.DOCK));
        assertSame(PanelContribution.empty(), PanelContribution.of("标题", new ArrayList<UiLine>(), null));
        assertTrue(PanelContribution.empty().isEmpty());
        assertNull(PanelContribution.empty().getTitle());
    }

    @Test
    @DisplayName("内容行不可变：快照会被渲染线程反复读取，不该能被改动")
    void getLines_should_beUnmodifiable() {
        List<UiLine> source = new ArrayList<UiLine>();
        source.add(UiLine.of("第一行"));
        PanelContribution contribution = PanelContribution.of("标题", source, null);
        source.add(UiLine.of("第二行"));
        assertEquals(1, contribution.getLines().size());
        assertThrows(UnsupportedOperationException.class, () -> contribution.getLines().add(UiLine.EMPTY));
    }

    @Test
    @DisplayName("多段行原样透传：面板内的强调由插件决定")
    void getLines_should_keepSegments() {
        PanelContribution contribution = PanelContribution.of("索引",
                Arrays.asList(UiLine.of(UiSegment.of("已索引 "), UiSegment.of("12", UiEmphasis.ACCENT))),
                UiRegion.DOCK);
        assertEquals("已索引 12", contribution.getLines().get(0).text());
    }

    @Test
    @DisplayName("有内容的贡献不是空贡献")
    void isEmpty_should_beFalse_when_linePresent() {
        assertFalse(PanelContribution.of("标题", Arrays.asList(UiLine.of("x")), null).isEmpty());
    }
}
