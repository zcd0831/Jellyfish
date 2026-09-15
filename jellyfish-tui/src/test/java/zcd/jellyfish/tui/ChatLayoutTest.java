package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatLayout} 的单元测试。
 * <p>
 * 本类是「版式的唯一真相」，因此这里不 mock 任何东西：直接用固定尺寸断言账本的数字。
 * 断言全部用终端尺寸而不是「大概比例」，因为账本的目的正是「与框架分配完全一致」——
 * 用比例断言就等于把口径模糊掉了。
 *
 * @author zcd
 */
@DisplayName("二维版式账本")
class ChatLayoutTest {

    /** 终端尺寸取定值时的输入面板行数（3 行内容 + 2 边框）。 */
    private static final int INPUT_ROWS = 5;

    /**
     * 构造一个面板。
     *
     * @param owner 贡献方
     * @param lines 内容行数
     * @param region 建议区域
     * @return 带归属的面板
     */
    private static OwnedPanel panelOf(String owner, int lines, UiRegion region) {
        List<UiLine> content = new ArrayList<UiLine>(lines);
        for (int i = 0; i < lines; i++) {
            content.add(UiLine.of("第 " + i + " 行"));
        }
        return new OwnedPanel(owner, PanelContribution.of("标题", content, region));
    }

    /**
     * 构造区域到面板的映射。
     *
     * @param pairs 区域与面板交替出现
     * @return 映射
     */
    private static Map<UiRegion, OwnedPanel> visibleOf(Object... pairs) {
        Map<UiRegion, OwnedPanel> map = new EnumMap<UiRegion, OwnedPanel>(UiRegion.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((UiRegion) pairs[i], (OwnedPanel) pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("没有面板时退化成既有版式：底部只有输入区与状态栏")
    void compute_should_degradeToLegacyLayout_when_noPanels() {
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, null);

        assertEquals(ChatLayout.STATUS_ROWS + INPUT_ROWS, layout.getBottomRows());
        assertEquals(0, layout.getTopRows());
        assertEquals(0, layout.getDockRows());
        assertEquals(0, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());
        assertEquals(100 - ChatLayout.BORDER * 2, layout.getMessageWidth());
        assertEquals(30 - ChatLayout.BORDER * 2 - INPUT_ROWS - ChatLayout.STATUS_ROWS, layout.getMessageRows());
    }

    @Test
    @DisplayName("停靠面板按内容行数加边框占高，并顶掉消息区同样多行")
    void compute_should_reserveRowsForDockPanel() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.DOCK, panelOf("p", 3, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible);

        assertEquals(3 + ChatLayout.BORDER * 2, layout.getDockRows());
        assertEquals(3 + ChatLayout.BORDER * 2 + INPUT_ROWS + ChatLayout.STATUS_ROWS, layout.getBottomRows());
        assertEquals(30 - ChatLayout.BORDER * 2 - layout.getBottomRows(), layout.getMessageRows());
    }

    @Test
    @DisplayName("单块面板的内容行数有上限：一个插件不能靠内容多撑高自己")
    void compute_should_capPanelContentRows() {
        Map<UiRegion, OwnedPanel> visible =
                visibleOf(UiRegion.DOCK, panelOf("greedy", 100, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 40, INPUT_ROWS, 0, visible);

        assertEquals(ChatLayout.PANEL_MAX_ROWS + ChatLayout.BORDER * 2, layout.getDockRows());
    }

    @Test
    @DisplayName("所有纵向面板合计超限时进一步收紧：区域上限 = min(终端高/3, 12)")
    void compute_should_capRegionRows() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK),
                UiRegion.TOP, panelOf("t", 8, UiRegion.TOP));

        // 终端高 30 → 区域上限 = min(10, 12) = 10
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible);

        assertEquals(10, layout.getDockRows());
        assertEquals(10, layout.getTopRows());
    }

    @Test
    @DisplayName("矮终端也要保住消息区下限：面板让路而不是把消息区挤成一条")
    void compute_should_keepMinMessageRows() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 12, INPUT_ROWS, 0, visible);

        assertEquals(ChatLayout.MIN_MESSAGE_ROWS, layout.getMessageRows());
    }

    @Test
    @DisplayName("浮层高度也算进底部：面板出现时不能把消息区内容顶掉一行")
    void compute_should_countOverlayRows() {
        ChatLayout without = ChatLayout.compute(100, 30, INPUT_ROWS, 0, null);
        ChatLayout with = ChatLayout.compute(100, 30, INPUT_ROWS, 4, null);

        assertEquals(4, with.getBottomRows() - without.getBottomRows());
        assertEquals(4, without.getMessageRows() - with.getMessageRows());
    }

    @Test
    @DisplayName("侧栏宽度按内容取宽并夹进 [20, W/4]")
    void compute_should_clampSidebarWidth() {
        Map<UiRegion, OwnedPanel> narrow = visibleOf(UiRegion.LEFT, panelOf("n", 1, UiRegion.LEFT));
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, narrow);

        // 内容很窄也要给到下限
        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getLeftWidth());
        assertEquals(100 - ChatLayout.BORDER * 2 - ChatLayout.SIDEBAR_MIN_WIDTH, layout.getMessageWidth());
    }

    @Test
    @DisplayName("侧栏内容很宽时封顶在 W/4")
    void compute_should_capSidebarWidth() {
        List<UiLine> wide = Collections.singletonList(UiLine.of(repeat('x', 200)));
        Map<UiRegion, OwnedPanel> visible = new EnumMap<UiRegion, OwnedPanel>(UiRegion.class);
        visible.put(UiRegion.LEFT, new OwnedPanel("w", PanelContribution.of("标题", wide, UiRegion.LEFT)));

        ChatLayout layout = ChatLayout.compute(200, 30, INPUT_ROWS, 0, visible);

        assertEquals(200 / ChatLayout.SIDEBAR_MAX_DIVISOR, layout.getLeftWidth());
    }

    @Test
    @DisplayName("终端窄于 80 列时侧栏整体隐藏：宁可没有侧栏，也不要把消息区压成窄条")
    void compute_should_hideSidebars_when_terminalTooNarrow() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        ChatLayout layout = ChatLayout.compute(70, 30, INPUT_ROWS, 0, visible);

        assertEquals(0, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());
        assertEquals(70 - ChatLayout.BORDER * 2, layout.getMessageWidth());
    }

    @Test
    @DisplayName("左右侧栏合计超限时保左栏、右栏归零：阅读顺序从左开始")
    void compute_should_dropRightSidebar_when_totalOverBudget() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        // W=100：单侧上限 25，合计上限 33；两个 20 不超合计，先看 20+20=40 > 33 → 砍右栏
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible);

        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());
    }

    @Test
    @DisplayName("消息区宽度不足时先砍右栏、再砍左栏，最后宁可窄也不给负数")
    void compute_should_shrinkMessageArea_thenDropSidebars() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        // W=80 恰好达到侧栏下限门槛：单侧 20、合计 26 → 砍右栏；消息区 = 80-2-20 = 58 ≥ 20
        ChatLayout layout = ChatLayout.compute(80, 30, INPUT_ROWS, 0, visible);
        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());

        // W=79：侧栏整体隐藏，消息区回到满宽
        ChatLayout narrow = ChatLayout.compute(79, 30, INPUT_ROWS, 0, visible);
        assertEquals(0, narrow.getLeftWidth());
        assertEquals(79 - ChatLayout.BORDER * 2, narrow.getMessageWidth());
    }

    @Test
    @DisplayName("单独查消息区宽度与完整账本一致：浮层折行依赖前者，两者口径不能分叉")
    void messageWidth_should_matchCompute() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT));

        assertEquals(ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible).getMessageWidth(),
                ChatLayout.messageWidth(100, visible));
        assertEquals(ChatLayout.compute(100, 30, INPUT_ROWS, 0, null).getMessageWidth(),
                ChatLayout.messageWidth(100, null));
    }

    @Test
    @DisplayName("极端尺寸不产生非正数：1 列 1 行的终端也要能渲染")
    void compute_should_neverReturnNonPositiveSizes() {
        ChatLayout layout = ChatLayout.compute(1, 1, 0, 0,
                visibleOf(UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK)));

        assertTrue(layout.getMessageWidth() >= 1);
        assertTrue(layout.getMessageRows() >= 1);
    }

    /**
     * 生成重复字符。
     *
     * @param ch    字符
     * @param count 次数
     * @return 字符串
     */
    private static String repeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }
}
