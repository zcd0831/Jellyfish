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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatShell} 中不依赖终端的纯逻辑的单元测试。
 * <p>
 * 真实的元素渲染不在这里断言（它要终端）；这里守的是「面板什么时候该让位」与
 * 「浮层占多少行」这两条账本口径——它们错了就会让消息区被顶掉一行或面板白消失。
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
    @DisplayName("没有浮层时面板照常显示")
    void visiblePanels_should_keepPanels_when_noOverlay() {
        Map<UiRegion, OwnedPanel> declared = docked();

        assertSame(declared, ChatShell.visiblePanels(declared, null));
        assertSame(declared, ChatShell.visiblePanels(declared, Overlay.none()));
    }

    @Test
    @DisplayName("模态浮层打开时面板整体让位：浮层是强交互，同屏只会把焦点搞散")
    void visiblePanels_should_hidePanels_when_overlayShown() {
        Overlay overlay = new Overlay(" 命令 ", Collections.singletonList(VisualLine.of("x")));

        assertTrue(ChatShell.visiblePanels(docked(), overlay).isEmpty());
    }

    @Test
    @DisplayName("让位只影响本帧：传进来的面板集合不被改写，落位状态不受影响")
    void visiblePanels_should_notMutateDeclared() {
        Map<UiRegion, OwnedPanel> declared = docked();
        Overlay overlay = new Overlay(" 命令 ", Collections.singletonList(VisualLine.of("x")));

        ChatShell.visiblePanels(declared, overlay);

        assertEquals(1, declared.size());
        assertTrue(declared.containsKey(UiRegion.DOCK));
    }

    @Test
    @DisplayName("没有面板时返回空映射，不返回 null")
    void visiblePanels_should_tolerateNull() {
        assertTrue(ChatShell.visiblePanels(null, null).isEmpty());
    }

    @Test
    @DisplayName("浮层打开时账本按「无面板」算：面板让位后消息区应当拿回整宽整高")
    void layout_should_reclaimSpace_when_panelsYield() {
        Overlay overlay = new Overlay(" 命令 ", Collections.singletonList(VisualLine.of("x")));
        Map<UiRegion, OwnedPanel> declared = docked();
        int dockRows = ChatLayout.panelRows(declared.get(UiRegion.DOCK));

        ChatLayout withPanels = ChatLayout.compute(100, 30, 5, ChatShell.overlayRows(overlay), declared);
        ChatLayout yielded = ChatLayout.compute(100, 30, 5, ChatShell.overlayRows(overlay),
                ChatShell.visiblePanels(declared, overlay));

        assertEquals(withPanels.getDockRows() - dockRows, yielded.getDockRows());
        assertEquals(withPanels.getMessageRows() + dockRows, yielded.getMessageRows());
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
