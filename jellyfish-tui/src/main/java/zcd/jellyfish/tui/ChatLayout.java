package zcd.jellyfish.tui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.Map;

/**
 * 二维版式账本：算出消息区还剩多少行列，以及各面板区域各占多少。
 * <p>
 * <b>为什么账本必须自己算</b>：{@code DockElement} 只能表达「底部占多少」，算不出「中间剩多少」，
 * 而消息区要看多少行、折到多宽必须由我们自己给出（{@code ChatState.view} 的入参就是它们）。
 * 因此这里与 {@link ChatShell} 是同一份账本的两端，本类的输出必须与 {@code ChatShell} 实际
 * 交给 {@code DockElement} 的约束完全一致。
 * <p>
 * <b>硬约束：全部用 {@code length(n)} 固定值，不用 {@code percent} / {@code fill}</b>。
 * 固定值下框架分配的 {@code center} 尺寸与我们算的完全一致；用百分比就要复刻框架的取整规则，
 * 迟早对不上——而这正是「消息区被顶掉一行、滚动位置与内容错位」这类问题的来源。
 * <p>
 * <b>计算顺序：先底／顶、后左右、最后消息区</b>。底部高度由输入框内容决定（既有约定），
 * 顶部／底部不参与宽度竞争；左右侧栏是宽度竞争，且侧栏高度就等于消息区行数，
 * 所以必须先确定行再确定列。
 * <p>
 * <b>每一条收缩规则都是为了消息区</b>：面板是插件想要的，消息区是用户要看的。因此
 * 纵向有 {@link #MIN_MESSAGE_ROWS} 与区域总高上限，横向有 {@link #MIN_MESSAGE_WIDTH}——
 * 预算不够时先砍右栏、再砍左栏，最后宁可窄也不给负数。插件无权把消息区挤没。
 * <p>
 * 纯函数，不可变，可单测。
 *
 * @author zcd
 */
public final class ChatLayout {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatLayout.class);

    /** 面板边框在每侧占用的列数/行数（与 {@link ChatShell#BORDER_SIZE} 同源）。 */
    static final int BORDER = ChatShell.BORDER_SIZE;

    /** 状态栏占用行数（与 {@link ChatShell#STATUS_ROWS} 同源）。 */
    static final int STATUS_ROWS = ChatShell.STATUS_ROWS;

    /** 消息区内容行数下限。 */
    static final int MIN_MESSAGE_ROWS = 5;

    /** 消息区内容列数下限。 */
    static final int MIN_MESSAGE_WIDTH = 20;

    /** 侧栏宽度下限（含边框）：再窄就放不下「索引 12/40」这类正常内容。 */
    static final int SIDEBAR_MIN_WIDTH = 20;

    /** 单侧侧栏宽度上限的分母：上限 = 终端宽 / 4。 */
    static final int SIDEBAR_MAX_DIVISOR = 4;

    /** 左右侧栏合计宽度的分母：上限 = 终端宽 / 3。 */
    static final int SIDEBAR_TOTAL_DIVISOR = 3;

    /** 终端窄于此值时侧栏整体隐藏：宁可没有侧栏，也不要把消息区压成窄条。 */
    static final int SIDEBAR_MIN_TERMINAL_WIDTH = 80;

    /** 单个面板的内容行数上限（不含边框）。 */
    static final int PANEL_MAX_ROWS = 8;

    /** 单个纵向区域（{@code DOCK} / {@code TOP}）的总高上限（与终端高度取小）。 */
    static final int REGION_MAX_ROWS_CAP = 12;

    /** 终端总列数。 */
    private final int terminalWidth;

    /** 底部占用行数（浮层 + 停靠面板 + 输入区 + 状态栏，含各自边框）。 */
    private final int bottomRows;

    /** 顶部占用行数（含边框）。 */
    private final int topRows;

    /** 消息区内容列数。 */
    private final int messageWidth;

    /** 消息区内容行数。 */
    private final int messageRows;

    /** 左侧栏宽度（含边框），0 表示不显示。 */
    private final int leftWidth;

    /** 右侧栏宽度（含边框），0 表示不显示。 */
    private final int rightWidth;

    /** 停靠区域占用行数（含边框）。 */
    private final int dockRows;

    /**
     * 构造账本。
     *
     * @param terminalWidth 终端总列数
     * @param bottomRows    底部占用行数
     * @param topRows       顶部占用行数
     * @param messageWidth  消息区内容列数
     * @param messageRows   消息区内容行数
     * @param leftWidth     左侧栏宽度
     * @param rightWidth    右侧栏宽度
     * @param dockRows      停靠区域占用行数（含边框）
     */
    private ChatLayout(int terminalWidth, int bottomRows, int topRows, int messageWidth, int messageRows,
                       int leftWidth, int rightWidth, int dockRows) {
        this.terminalWidth = terminalWidth;
        this.bottomRows = bottomRows;
        this.topRows = topRows;
        this.messageWidth = messageWidth;
        this.messageRows = messageRows;
        this.leftWidth = leftWidth;
        this.rightWidth = rightWidth;
        this.dockRows = dockRows;
    }

    /**
     * 计算一帧的版式账本。
     *
     * @param terminalWidth  终端总列数
     * @param terminalHeight 终端总行数
     * @param inputPanelRows 输入面板占用行数（含边框）
     * @param overlayRows    模态浮层占用行数（含边框），无浮层时为 0
     * @param visible        每个区域当前选中的面板，可为 {@code null} 或空
     * @return 账本，保证非 {@code null}
     */
    public static ChatLayout compute(int terminalWidth, int terminalHeight, int inputPanelRows,
                                     int overlayRows, Map<UiRegion, OwnedPanel> visible) {
        int width = Math.max(1, terminalWidth);
        int height = Math.max(1, terminalHeight);
        // 纵向区域的总高上限：终端很矮时进一步收紧，避免面板把消息区挤到只剩 MIN_MESSAGE_ROWS
        int regionLimit = Math.min(height / 3, REGION_MAX_ROWS_CAP);

        int dockRows = Math.min(panelRows(panelIn(visible, UiRegion.DOCK)), regionLimit);
        int topRows = Math.min(panelRows(panelIn(visible, UiRegion.TOP)), regionLimit);
        int bottomRows = Math.max(0, overlayRows) + dockRows
                + Math.max(0, inputPanelRows) + STATUS_ROWS;
        int messageRows = Math.max(MIN_MESSAGE_ROWS, height - BORDER * 2 - bottomRows - topRows);

        Sidebars sidebars = sidebarsOf(width, visible);
        if (sidebars.overBudget) {
            // 保左栏：阅读顺序从左开始，右侧面板让位。真机若嫌右栏老被砍掉，调的是这里的策略而不是账本公式
            LOG.warn("左右侧栏合计超出宽度预算，本次只显示左栏");
        }
        return new ChatLayout(width, bottomRows, topRows, sidebars.messageWidth, messageRows,
                sidebars.left, sidebars.right, dockRows);
    }

    /**
     * 计算消息区内容列数。
     * <p>
     * 单独开这个入口是因为<b>浮层面板的折行宽度依赖消息区宽度，而消息区宽度不依赖浮层高度</b>——
     * 先算宽度、再用它折行浮层、最后算完整账本，可以避免「先假定一个宽度、拿到高度后宽度又变了」
     * 这种自相矛盾的状态。
     *
     * @param terminalWidth 终端总列数
     * @param visible       每个区域当前选中的面板，可为 {@code null} 或空
     * @return 内容列数，最小为 1
     */
    public static int messageWidth(int terminalWidth, Map<UiRegion, OwnedPanel> visible) {
        return sidebarsOf(Math.max(1, terminalWidth), visible).messageWidth;
    }

    /**
     * 计算左右侧栏与消息区的宽度分配。
     * <p>
     * 顺序：先按内容取宽并夹进单侧区间，再检查合计上限，最后检查消息区下限——每一层都可能把
     * 侧栏归零，而消息区宽度只在最后兜一次底（宁可窄也不给负数）。
     *
     * @param width   终端总列数（已保证不小于 1）
     * @param visible 每个区域当前选中的面板，可为 {@code null}
     * @return 宽度分配结果
     */
    private static Sidebars sidebarsOf(int width, Map<UiRegion, OwnedPanel> visible) {
        int limit = width / SIDEBAR_MAX_DIVISOR;
        int left = sidebarWidth(panelIn(visible, UiRegion.LEFT), width, limit);
        int right = sidebarWidth(panelIn(visible, UiRegion.RIGHT), width, limit);
        boolean overBudget = left + right > width / SIDEBAR_TOTAL_DIVISOR;
        if (overBudget) {
            right = 0;
        }
        int messageWidth = width - BORDER * 2 - left - right;
        if (messageWidth < MIN_MESSAGE_WIDTH && right > 0) {
            right = 0;
            messageWidth = width - BORDER * 2 - left;
        }
        if (messageWidth < MIN_MESSAGE_WIDTH && left > 0) {
            left = 0;
            messageWidth = width - BORDER * 2;
        }
        return new Sidebars(left, right, Math.max(1, messageWidth), overBudget);
    }

    /**
     * 计算一个面板占用的总行数（含边框）。
     *
     * @param panel 面板，可为 {@code null}
     * @return 行数；无面板时为 0
     */
    static int panelRows(OwnedPanel panel) {
        if (panel == null) {
            return 0;
        }
        PanelContribution contribution = panel.getContribution();
        return Math.min(UiRender.contentRows(contribution), PANEL_MAX_ROWS) + BORDER * 2;
    }

    /**
     * 计算一个侧栏的宽度（含边框）。
     * <p>
     * 终端太窄时整体隐藏：侧栏是「锦上添花」，而消息区是主体，宽度不足时应当先保主体。
     *
     * @param panel         面板，可为 {@code null}
     * @param terminalWidth 终端总列数
     * @param limit         单侧宽度上限
     * @return 宽度；不显示时为 0
     */
    private static int sidebarWidth(OwnedPanel panel, int terminalWidth, int limit) {
        if (panel == null || terminalWidth < SIDEBAR_MIN_TERMINAL_WIDTH) {
            return 0;
        }
        int desired = UiRender.contentWidth(panel.getContribution()) + BORDER * 2;
        int upper = Math.max(SIDEBAR_MIN_WIDTH, limit);
        return Math.min(Math.max(desired, SIDEBAR_MIN_WIDTH), upper);
    }

    /**
     * 取某区域选中的面板。
     *
     * @param visible 区域到面板的映射，可为 {@code null}
     * @param region  区域
     * @return 面板；该区域没有面板时为 {@code null}
     */
    private static OwnedPanel panelIn(Map<UiRegion, OwnedPanel> visible, UiRegion region) {
        return visible == null ? null : visible.get(region);
    }

    /**
     * 获取终端总列数。
     *
     * @return 列数
     */
    public int getTerminalWidth() {
        return terminalWidth;
    }

    /**
     * 获取底部占用行数。
     *
     * @return 行数（浮层 + 停靠面板 + 输入区 + 状态栏）
     */
    public int getBottomRows() {
        return bottomRows;
    }

    /**
     * 获取顶部占用行数。
     *
     * @return 行数
     */
    public int getTopRows() {
        return topRows;
    }

    /**
     * 获取停靠区域占用行数。
     *
     * @return 行数（含边框）
     */
    public int getDockRows() {
        return dockRows;
    }

    /**
     * 获取消息区内容列数。
     *
     * @return 列数
     */
    public int getMessageWidth() {
        return messageWidth;
    }

    /**
     * 获取消息区内容行数。
     *
     * @return 行数
     */
    public int getMessageRows() {
        return messageRows;
    }

    /**
     * 获取左侧栏宽度。
     *
     * @return 宽度（含边框）；不显示时为 0
     */
    public int getLeftWidth() {
        return leftWidth;
    }

    /**
     * 获取右侧栏宽度。
     *
     * @return 宽度（含边框）；不显示时为 0
     */
    public int getRightWidth() {
        return rightWidth;
    }

    @Override
    public String toString() {
        return "ChatLayout{message=" + messageWidth + "x" + messageRows
                + ", top=" + topRows + ", bottom=" + bottomRows
                + ", dock=" + dockRows + ", left=" + leftWidth + ", right=" + rightWidth + '}';
    }

    /**
     * 左右侧栏与消息区的宽度分配结果。
     *
     * @author zcd
     */
    private static final class Sidebars {

        /** 左侧栏宽度（含边框）。 */
        private final int left;

        /** 右侧栏宽度（含边框）。 */
        private final int right;

        /** 消息区内容列数。 */
        private final int messageWidth;

        /** 是否因为合计超限而牺牲了右栏（由调用方决定要不要记日志，避免同一帧记两条）。 */
        private final boolean overBudget;

        /**
         * 构造宽度分配结果。
         *
         * @param left        左侧栏宽度
         * @param right       右侧栏宽度
         * @param messageWidth 消息区内容列数
         * @param overBudget  是否因合计超限牺牲了右栏
         */
        private Sidebars(int left, int right, int messageWidth, boolean overBudget) {
            this.left = left;
            this.right = right;
            this.messageWidth = messageWidth;
            this.overBudget = overBudget;
        }
    }
}
