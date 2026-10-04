package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatShell} 中不依赖终端的纯逻辑的单元测试。
 * <p>
 * 真实的元素渲染不在这里断言（它要终端）；这里守的是「浮层占多少行」以及
 * 「浮层打开时面板是变矮而不是消失」这两条账本口径——它们错了就会让消息区被顶掉一行、
 * 或者面板白消失。
 *
 * @author zcd
 */
@DisplayName("ChatShell 版式口径")
class ChatShellTest {

    /**
     * 构造一个面板。
     *
     * @param owner 贡献方
     * @param region 建议区域
     * @return 带归属的面板
     */
    private static OwnedPanel panelOf(String owner, UiRegion region) {
        return new OwnedPanel(owner, PanelContribution.of("标题",
                Collections.singletonList(UiLine.of("正文")), region));
    }

    /**
     * 构造含一个停靠面板的区域映射。
     *
     * @return 映射
     */
    private static Map<UiRegion, OwnedPanel> docked() {
        Map<UiRegion, OwnedPanel> map = new EnumMap<UiRegion, OwnedPanel>(UiRegion.class);
        map.put(UiRegion.DOCK, panelOf("p", UiRegion.DOCK));
        return map;
    }

    /**
     * 构造「只有左右侧栏」的区域映射。
     * <p>
     * 断言「侧栏变矮了多少」时必须用它：若同时含 {@code DOCK} / {@code TOP}，浮层打开时消息区
     * 会同时被浮层与纵向面板两头压，两个方向的变化叠在一起就测不出真正想钉的那条。
     *
     * @return 映射
     */
    private static Map<UiRegion, OwnedPanel> sidebarsOnly() {
        Map<UiRegion, OwnedPanel> map = new EnumMap<UiRegion, OwnedPanel>(UiRegion.class);
        map.put(UiRegion.LEFT, panelOf("l", UiRegion.LEFT));
        map.put(UiRegion.RIGHT, panelOf("r", UiRegion.RIGHT));
        return map;
    }

    /**
     * 构造一个非空浮层。
     *
     * @return 浮层
     */
    private static Overlay overlay() {
        return new Overlay(" 命令 ", Collections.singletonList(VisualLine.of("x")));
    }

    @Test
    @DisplayName("空浮层不占行：否则消息区会被白顶掉两行")
    void overlayRows_should_beZero_when_noContent() {
        assertEquals(0, ChatShell.overlayRows(null));
        assertEquals(0, ChatShell.overlayRows(Overlay.none()));
    }

    @Test
    @DisplayName("浮层行数 = 内容行数 + 上下边框")
    void overlayRows_should_countBorder() {
        Overlay overlay = new Overlay(" 命令 ", Collections.singletonList(VisualLine.of("x")));

        assertEquals(1 + ChatShell.BORDER_SIZE * 2, ChatShell.overlayRows(overlay));
    }

    @Test
    @DisplayName("浮层打开时侧栏留下并自己变矮：不消失，只是把行数让给浮层")
    void layout_should_shrinkSidebars_when_overlayShown() {
        Map<UiRegion, OwnedPanel> declared = sidebarsOnly();
        int overlayRows = ChatShell.overlayRows(overlay());

        // 用 202 列（实测终端）：窄于 minWidthForBothSidebars() 时右栏本来就会因为宽度被舍掉，
        // 那是另一条判据，会把这个用例想验的「浮层导致的差别」盖住
        ChatLayout without = ChatLayout.compute(202, 40, 5, 0, declared);
        ChatLayout with = ChatLayout.compute(202, 40, 5, overlayRows, declared);

        assertTrue(with.getRightWidth() > 0, "右栏必须还在——这正是「不消失」");
        assertEquals(without.getRightWidth(), with.getRightWidth(), "浮层只影响高度，不该动侧栏宽度");
        assertEquals(overlayRows, without.getMessageRows() - with.getMessageRows(),
                "侧栏高度取自消息区，因此浮层占的行数正好是侧栏变矮的幅度");
    }

    @Test
    @DisplayName("浮层打开时纵向面板留下并按预算变矮：抢的是行数，不是可见性")
    void layout_should_shrinkVerticalPanel_when_overlayShown() {
        Map<UiRegion, OwnedPanel> declared = docked();
        int desired = ChatLayout.panelRows(declared.get(UiRegion.DOCK));

        ChatLayout without = ChatLayout.compute(100, 30, 5, 0, declared);
        ChatLayout with = ChatLayout.compute(100, 30, 5, ChatShell.overlayRows(overlay()), declared);

        assertEquals(desired, without.getDockRows(), "没有浮层时按内容占满");
        assertEquals(desired, with.getDockRows(),
                "高 30 的终端装得下：浮层出现时 DOCK 不该被抽掉");
        assertEquals(ChatShell.overlayRows(overlay()), without.getMessageRows() - with.getMessageRows(),
                "浮层占的行数从消息区里出，DOCK 的高度不跟着变");
    }

    @Test
    @DisplayName("补全浮层按内容构造，无内容时返回空浮层")
    void completionOverlay_should_beEmptyWithoutLines() {
        assertTrue(ChatShell.completionOverlay(null).isEmpty());
        assertTrue(ChatShell.completionOverlay(Collections.<VisualLine>emptyList()).isEmpty());
        assertEquals(1, ChatShell.completionOverlay(Collections.singletonList(VisualLine.of("x")))
                .getLines().size());
    }
}
