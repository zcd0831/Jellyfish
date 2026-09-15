package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiPlacement} 的单元测试。
 * <p>
 * 这些断言守的是「多插件抢一块区域时到底谁显示」——它是插件的界面能不能被用户控制住的唯一保证，
 * 因此既覆盖默认规则（按 {@code order}），也覆盖用户指定（{@code /ui}）的优先级。
 *
 * @author zcd
 */
@DisplayName("面板落位仲裁")
class UiPlacementTest {

    /**
     * 构造一个面板。
     *
     * @param owner  贡献方
     * @param region 建议区域，可为 {@code null}
     * @return 带归属的面板
     */
    private static OwnedPanel panelOf(String owner, UiRegion region) {
        return new OwnedPanel(owner, PanelContribution.of(owner,
                Collections.singletonList(UiLine.of("正文")), region));
    }

    @Test
    @DisplayName("没有建议区域时落到 DOCK：最通用的落点")
    void candidates_should_fallBackToDock_when_noPreference() {
        UiPlacement placement = new UiPlacement();

        Map<UiRegion, List<OwnedPanel>> byRegion =
                placement.candidates(Collections.singletonList(panelOf("p", null)));

        assertEquals(Collections.singletonList("p"), ownersOf(byRegion.get(UiRegion.DOCK)));
    }

    @Test
    @DisplayName("建议是面板区域时按建议落位")
    void candidates_should_honorPreferredRegion() {
        UiPlacement placement = new UiPlacement();

        Map<UiRegion, List<OwnedPanel>> byRegion =
                placement.candidates(Collections.singletonList(panelOf("p", UiRegion.LEFT)));

        assertTrue(byRegion.containsKey(UiRegion.LEFT));
    }

    @Test
    @DisplayName("建议 STATUS 时落回 DOCK：状态栏是拼接型，不是面板区域")
    void candidates_should_rejectStatusRegion() {
        UiPlacement placement = new UiPlacement();

        Map<UiRegion, List<OwnedPanel>> byRegion =
                placement.candidates(Collections.singletonList(panelOf("p", UiRegion.STATUS)));

        assertTrue(byRegion.containsKey(UiRegion.DOCK));
        assertFalse(byRegion.containsKey(UiRegion.STATUS));
    }

    @Test
    @DisplayName("同一区域多个候选时默认显示 order 最小者（即列表首位）")
    void selected_should_pickLowestOrderByDefault() {
        UiPlacement placement = new UiPlacement();

        Map<UiRegion, OwnedPanel> selected = placement.selected(
                Arrays.asList(panelOf("first", UiRegion.DOCK), panelOf("second", UiRegion.DOCK)));

        assertEquals("first", selected.get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("用户指定后按用户为准，而不是按 order：否则 /ui 切换会静默失效")
    void selected_should_honorUserChoice() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels =
                Arrays.asList(panelOf("first", UiRegion.DOCK), panelOf("second", UiRegion.DOCK));

        placement.assign("second", UiRegion.DOCK);

        assertEquals("second", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("用户指定可以把面板挪出它建议的区域")
    void assign_should_overridePreferredRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = Collections.singletonList(panelOf("p", UiRegion.LEFT));

        placement.assign("p", UiRegion.RIGHT);

        assertEquals("p", placement.selected(panels).get(UiRegion.RIGHT).getOwner());
        assertNull(placement.selected(panels).get(UiRegion.LEFT));
    }

    @Test
    @DisplayName("关闭的区域不显示，但候选仍在：关掉再打开不需要重新收集")
    void hide_should_keepCandidates() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = Collections.singletonList(panelOf("p", UiRegion.DOCK));

        placement.hide(UiRegion.DOCK);

        assertTrue(placement.isHidden(UiRegion.DOCK));
        assertNull(placement.selected(panels).get(UiRegion.DOCK));
        assertEquals(Collections.singletonList("p"), ownersOf(placement.candidates(panels).get(UiRegion.DOCK)));
    }

    @Test
    @DisplayName("恢复显示时清掉该区域的用户指定：关掉再打开应当回到默认而不是回到旧选择")
    void show_should_resetAssignedForRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels =
                Arrays.asList(panelOf("first", UiRegion.DOCK), panelOf("second", UiRegion.DOCK));
        placement.assign("second", UiRegion.DOCK);
        placement.hide(UiRegion.DOCK);

        placement.show(UiRegion.DOCK);

        assertFalse(placement.isHidden(UiRegion.DOCK));
        assertEquals("first", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("轮换从当前显示者出发到下一个候选")
    void cycle_should_moveToNextCandidate() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels =
                Arrays.asList(panelOf("a", UiRegion.DOCK), panelOf("b", UiRegion.DOCK),
                        panelOf("c", UiRegion.DOCK));

        assertEquals("a", placement.selected(panels).get(UiRegion.DOCK).getOwner());
        assertEquals("b", placement.cycle(UiRegion.DOCK, panels).getOwner());
        assertEquals("c", placement.cycle(UiRegion.DOCK, panels).getOwner());
        assertEquals("a", placement.cycle(UiRegion.DOCK, panels).getOwner());
    }

    @Test
    @DisplayName("只分配到一个候选时轮换回到它自己，不会消失")
    void cycle_should_stayOnSameCandidate_when_onlyOne() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = Collections.singletonList(panelOf("only", UiRegion.DOCK));

        assertEquals("only", placement.cycle(UiRegion.DOCK, panels).getOwner());
        assertEquals("only", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("区域没有候选时轮换返回 null，不是抛异常")
    void cycle_should_returnNull_when_noCandidates() {
        assertNull(new UiPlacement().cycle(UiRegion.LEFT, Collections.<OwnedPanel>emptyList()));
    }

    @Test
    @DisplayName("轮换会解除关闭状态：用户要求轮换就是在要求显示")
    void cycle_should_unhideRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = Collections.singletonList(panelOf("p", UiRegion.DOCK));
        placement.hide(UiRegion.DOCK);

        placement.cycle(UiRegion.DOCK, panels);

        assertFalse(placement.isHidden(UiRegion.DOCK));
    }

    @Test
    @DisplayName("不能把面板指定到状态栏：它是拼接型区域")
    void assign_should_rejectStatusRegion() {
        assertThrows(JellyfishException.class, () -> new UiPlacement().assign("p", UiRegion.STATUS));
        assertThrows(JellyfishException.class, () -> new UiPlacement().cycle(UiRegion.STATUS,
                Collections.<OwnedPanel>emptyList()));
    }

    @Test
    @DisplayName("没有面板时区域映射为空而不是 null")
    void candidates_should_tolerateNull() {
        assertTrue(new UiPlacement().candidates(null).isEmpty());
        assertTrue(new UiPlacement().selected(null).isEmpty());
    }

    /**
     * 取出面板列表里的 owner。
     *
     * @param panels 面板列表
     * @return owner 列表
     */
    private static List<String> ownersOf(List<OwnedPanel> panels) {
        List<String> owners = new ArrayList<String>();
        for (OwnedPanel panel : panels) {
            owners.add(panel.getOwner());
        }
        return owners;
    }
}
