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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 命令输出面板按内容上限想要的总行数（含边框）。 */
    private static final int SHELL_PANEL_ROWS = ShellOutput.MAX_CONTENT_ROWS + ChatLayout.BORDER * 2;

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
     * 构造一个「内容很宽」的面板。
     * <p>
     * 侧栏宽度由内容决定，因此要用它才能把侧栏顶到单侧上限——{@link #panelOf} 的内容只有 7 列，
     * 一律被夹到下限 20，覆盖不到「左栏很宽」这条真实路径。
     *
     * @param owner  贡献方
     * @param cols   内容显示列数
     * @param region 建议区域
     * @return 带归属的面板
     */
    private static OwnedPanel widePanelOf(String owner, int cols, UiRegion region) {
        List<UiLine> content = Collections.singletonList(UiLine.of(repeat('x', cols)));
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
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, null, 0);

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

        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(3 + ChatLayout.BORDER * 2, layout.getDockRows());
        assertEquals(3 + ChatLayout.BORDER * 2 + INPUT_ROWS + ChatLayout.STATUS_ROWS, layout.getBottomRows());
        assertEquals(30 - ChatLayout.BORDER * 2 - layout.getBottomRows(), layout.getMessageRows());
    }

    @Test
    @DisplayName("命令输出面板与插件停靠面板互斥：它在场时按它的高度算，不给插件面板留行")
    void compute_should_preferShellOutputPanel_overPluginDockPanel() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.DOCK, panelOf("plugin", 3, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible, SHELL_PANEL_ROWS);

        assertEquals(SHELL_PANEL_ROWS, layout.getDockRows(),
                "命令输出是用户刚敲下那一下的回应，优先级高于常驻的插件面板");
    }

    @Test
    @DisplayName("命令输出面板同样受纵向预算约束：分不够就变矮，消息区下限不许被挤穿")
    void compute_should_boundShellOutputPanel_by_verticalBudget() {
        // 高 18：区域上限 min(6,12)=6，而扣除消息区下限（5+2 边框）与底部固定段（输入 5 + 状态栏 1）
        // 之后只剩 5 行可分——面板必须接着变矮，而不是把整帧撑出终端
        ChatLayout layout = ChatLayout.compute(100, 18, INPUT_ROWS, 0, null, SHELL_PANEL_ROWS);

        assertTrue(layout.getDockRows() < SHELL_PANEL_ROWS, "面板按预算变矮");
        assertEquals(ChatLayout.MIN_MESSAGE_ROWS, layout.getMessageRows());
        assertEquals(18, layout.getTopRows() + layout.getMessageRows() + ChatLayout.BORDER * 2
                + layout.getBottomRows(), "整帧必须正好等于终端高度");
    }

    @Test
    @DisplayName("单块面板的内容行数有上限：一个插件不能靠内容多撑高自己")
    void compute_should_capPanelContentRows() {
        Map<UiRegion, OwnedPanel> visible =
                visibleOf(UiRegion.DOCK, panelOf("greedy", 100, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 40, INPUT_ROWS, 0, visible, 0);

        assertEquals(ChatLayout.PANEL_MAX_ROWS + ChatLayout.BORDER * 2, layout.getDockRows());
    }

    @Test
    @DisplayName("纵向面板按预算分配：分不够时先矮 TOP，整帧不许超出终端高度")
    void compute_should_shareVerticalBudget_whenPanelsExceedHeight() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK),
                UiRegion.TOP, panelOf("t", 8, UiRegion.TOP));

        // 终端高 30：区域上限 min(10,12)=10，两者各想要 10；扣除消息区下限（5+2 边框）
        // 与底部固定段（输入 5 + 状态栏 1）后，面板只分得到 17 行
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(10, layout.getDockRows(), "DOCK 优先，拿到自己的上限");
        assertEquals(7, layout.getTopRows(), "TOP 拿剩下的——分不够就变矮，而不是把它抽掉");
        assertEquals(ChatLayout.MIN_MESSAGE_ROWS, layout.getMessageRows());
        assertEquals(30, layout.getTopRows() + layout.getMessageRows() + ChatLayout.BORDER * 2
                        + layout.getBottomRows(),
                "整帧必须正好等于终端高度：多出来的部分会把底部（审批提示 / 输入框）裁掉");
    }

    @Test
    @DisplayName("矮终端上面板分不到行数就归零：只剩边框的面板没有意义，而消息区下限不许被挤穿")
    void compute_should_keepMinMessageRows() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK));

        ChatLayout layout = ChatLayout.compute(100, 12, INPUT_ROWS, 0, visible, 0);

        assertEquals(0, layout.getDockRows(), "高 12 装不下任何面板：归零而不是画一条边框");
        assertEquals(ChatLayout.MIN_MESSAGE_ROWS, layout.getMessageRows());
    }

    @Test
    @DisplayName("浮层高度也算进底部：面板出现时不能把消息区内容顶掉一行")
    void compute_should_countOverlayRows() {
        ChatLayout without = ChatLayout.compute(100, 30, INPUT_ROWS, 0, null, 0);
        ChatLayout with = ChatLayout.compute(100, 30, INPUT_ROWS, 4, null, 0);

        assertEquals(4, with.getBottomRows() - without.getBottomRows());
        assertEquals(4, without.getMessageRows() - with.getMessageRows());
    }

    @Test
    @DisplayName("侧栏宽度按内容取宽并夹进 [20, W/4]")
    void compute_should_clampSidebarWidth() {
        Map<UiRegion, OwnedPanel> narrow = visibleOf(UiRegion.LEFT, panelOf("n", 1, UiRegion.LEFT));
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, narrow, 0);

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

        ChatLayout layout = ChatLayout.compute(200, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(200 / ChatLayout.SIDEBAR_MAX_DIVISOR, layout.getLeftWidth());
    }

    @Test
    @DisplayName("终端窄于 80 列时侧栏整体隐藏：宁可没有侧栏，也不要把消息区压成窄条")
    void compute_should_hideSidebars_when_terminalTooNarrow() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        ChatLayout layout = ChatLayout.compute(70, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(0, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());
        assertEquals(70 - ChatLayout.BORDER * 2, layout.getMessageWidth());
    }

    @Test
    @DisplayName("宽度预算放不下两个侧栏下限时保左栏、舍右栏：阅读顺序从左开始")
    void compute_should_dropRightSidebar_when_budgetCannotHoldBothFloors() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        // W=100：单侧上限 25，合计上限 33；两个下限 20+20=40 > 33 → 放不下，只能舍右栏
        ChatLayout layout = ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());
    }

    @Test
    @DisplayName("合计超限但装得下两个下限时两栏共存：待办变宽不该把右栏整块吃掉")
    void compute_should_keepBothSidebars_when_leftPanelIsWide() {
        // 202 列是实测终端：单侧上限 50、合计上限 67。左栏被长待办条目顶到上限 50 之后，
        // 50 + 20 > 67 就会触发超限——旧实现直接 right = 0，右栏那块常驻面板整块消失
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, widePanelOf("todo", 60, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("stock", 1, UiRegion.RIGHT));

        ChatLayout layout = ChatLayout.compute(202, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(202 / ChatLayout.SIDEBAR_TOTAL_DIVISOR - ChatLayout.SIDEBAR_MIN_WIDTH,
                layout.getLeftWidth(), "右栏保到下限，余下给左栏");
        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getRightWidth(), "右栏必须还在");
        assertEquals(202 - ChatLayout.BORDER * 2 - layout.getLeftWidth() - layout.getRightWidth(),
                layout.getMessageWidth());
    }

    @Test
    @DisplayName("只有一块侧栏时不被压窄：为竞争场景付的代价不能落在单栏上")
    void compute_should_notNarrowSidebar_when_onlyOnePanel() {
        Map<UiRegion, OwnedPanel> visible =
                visibleOf(UiRegion.LEFT, widePanelOf("todo", 60, UiRegion.LEFT));

        ChatLayout layout = ChatLayout.compute(202, 30, INPUT_ROWS, 0, visible, 0);

        assertEquals(202 / ChatLayout.SIDEBAR_MAX_DIVISOR, layout.getLeftWidth(),
                "右栏空着时左栏应当能用到自己的上限");
        assertEquals(0, layout.getRightWidth());
    }

    @Test
    @DisplayName("窄终端上先舍右栏、再整体隐藏侧栏，且消息区始终不为负")
    void compute_should_shrinkMessageArea_thenDropSidebars() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        // W=80 恰好达到侧栏下限门槛：单侧 20、合计 26 < 40 → 放不下两个下限，舍右栏；
        // 消息区 = 80-2-20 = 58 ≥ 20
        ChatLayout layout = ChatLayout.compute(80, 30, INPUT_ROWS, 0, visible, 0);
        assertEquals(ChatLayout.SIDEBAR_MIN_WIDTH, layout.getLeftWidth());
        assertEquals(0, layout.getRightWidth());

        // W=79：侧栏整体隐藏，消息区回到满宽
        ChatLayout narrow = ChatLayout.compute(79, 30, INPUT_ROWS, 0, visible, 0);
        assertEquals(0, narrow.getLeftWidth());
        assertEquals(79 - ChatLayout.BORDER * 2, narrow.getMessageWidth());
    }

    @Test
    @DisplayName("单独查消息区宽度与完整账本一致：浮层折行依赖前者，两者口径不能分叉")
    void messageWidth_should_matchCompute() {
        Map<UiRegion, OwnedPanel> visible = visibleOf(UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT));

        assertEquals(ChatLayout.compute(100, 30, INPUT_ROWS, 0, visible, 0).getMessageWidth(),
                ChatLayout.messageWidth(100, visible));
        assertEquals(ChatLayout.compute(100, 30, INPUT_ROWS, 0, null, 0).getMessageWidth(),
                ChatLayout.messageWidth(100, null));
    }

    @Test
    @DisplayName("极端尺寸不产生非正数：1 列 1 行的终端也要能渲染")
    void compute_should_neverReturnNonPositiveSizes() {
        ChatLayout layout = ChatLayout.compute(1, 1, 0, 0,
                visibleOf(UiRegion.DOCK, panelOf("d", 8, UiRegion.DOCK)), SHELL_PANEL_ROWS);

        assertTrue(layout.getMessageWidth() >= 1);
        assertTrue(layout.getMessageRows() >= 1);
    }

    @Test
    @DisplayName("舍没舍右栏要如实报出来：外壳靠它去重那条降级日志")
    void isRightSidebarDropped_should_reportTheDegradation() {
        Map<UiRegion, OwnedPanel> both = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        // W=100：两个下限 40 > 合计上限 33，舍右栏 → 要报出来
        assertTrue(ChatLayout.compute(100, 30, INPUT_ROWS, 0, both, 0).isRightSidebarDropped());

        // W=202：两栏共存 → 不报
        Map<UiRegion, OwnedPanel> wide = visibleOf(
                UiRegion.LEFT, widePanelOf("todo", 60, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("stock", 1, UiRegion.RIGHT));
        assertFalse(ChatLayout.compute(202, 30, INPUT_ROWS, 0, wide, 0).isRightSidebarDropped());

        // 只有一块侧栏时右栏本就是空的，不算「被舍掉」
        Map<UiRegion, OwnedPanel> single =
                visibleOf(UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT));
        assertFalse(ChatLayout.compute(100, 30, INPUT_ROWS, 0, single, 0).isRightSidebarDropped(),
                "右栏本来就没有面板，不该被报成降级");
    }

    @Test
    @DisplayName("两栏共存的最小终端宽度：日志里的建议宽度与实际判据必须一致")
    void minWidthForBothSidebars_should_matchTheActualThreshold() {
        int threshold = ChatLayout.minWidthForBothSidebars();
        Map<UiRegion, OwnedPanel> both = visibleOf(
                UiRegion.LEFT, panelOf("l", 1, UiRegion.LEFT),
                UiRegion.RIGHT, panelOf("r", 1, UiRegion.RIGHT));

        assertTrue(ChatLayout.compute(threshold, 30, INPUT_ROWS, 0, both, 0).getRightWidth() > 0,
                "恰好达到阈值时右栏就应当在");
        assertTrue(ChatLayout.compute(threshold - 1, 30, INPUT_ROWS, 0, both, 0).isRightSidebarDropped(),
                "差一列就应当退化——否则日志里那个建议宽度是错的");
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
