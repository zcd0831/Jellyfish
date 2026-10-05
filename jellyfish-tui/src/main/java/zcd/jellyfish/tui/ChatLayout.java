package zcd.jellyfish.tui;

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
 * 纵向有 {@link #MIN_MESSAGE_ROWS} 与区域总高上限、横向有 {@link #MIN_MESSAGE_WIDTH}——
 * 预算不够时先把侧栏按内容夹进单侧与合计上限，再依次舍右栏、左栏；纵向面板则在
 * 「总高扣除消息区下限与不可让位的底部各段」剩下的预算里按优先级分配，分不够就变矮、
 * 分不到一块面板就没有它。最后宁可窄也不给负数。插件无权把消息区挤没。
 * <p>
 * <b>整帧高度必须正好等于终端高度</b>：各段加起来少一行，框架就会把底部（审批框的键位提示、
 * 输入框）裁掉，或多占一行把整帧顶出屏幕。用 {@code max(下限)} 把消息区撑大只是让账本自相矛盾，
 * 并不阻止框架多占行——账本必须自己保证加起来正好。
 * <p>
 * 纯函数，不可变，可单测：<b>它因此不记日志</b>——本类每渲染一帧就被调用一次，而日志是按「状态
 * 变化」而不是「被调用」才有意义的（否则同一句话每秒刷几十遍）。它把「本帧舍了右栏」如实报出去
 * （{@link #isRightSidebarDropped()}），由外壳按帧间变化去记。
 *
 * @author zcd
 */
public final class ChatLayout {

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

    /** 一个纵向面板最少要占的行数（1 行内容 + 上下边框）：少于它就不该占位。 */
    static final int MIN_PANEL_ROWS = 1 + BORDER * 2;

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

    /** 本帧是否因为宽度预算放不下两个侧栏下限而舍掉了右栏。 */
    private final boolean rightSidebarDropped;

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
     * @param rightSidebarDropped 本帧是否舍掉了右栏
     */
    private ChatLayout(int terminalWidth, int bottomRows, int topRows, int messageWidth, int messageRows,
                       int leftWidth, int rightWidth, int dockRows, boolean rightSidebarDropped) {
        this.terminalWidth = terminalWidth;
        this.bottomRows = bottomRows;
        this.topRows = topRows;
        this.messageWidth = messageWidth;
        this.messageRows = messageRows;
        this.leftWidth = leftWidth;
        this.rightWidth = rightWidth;
        this.dockRows = dockRows;
        this.rightSidebarDropped = rightSidebarDropped;
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

        // 不可让位的底部各段（浮层 / 输入区 / 状态栏）：它们与纵向面板抢的是同一摞空间
        int fixedBottom = Math.max(0, overlayRows) + Math.max(0, inputPanelRows) + STATUS_ROWS;
        // 面板可分的纵向预算 = 总高 − 消息区下限（含它自己的边框）− 不可让位的底部。
        // 面板只许在这里面分，因此消息区永远不会被挤到下限以下。
        int panelBudget = Math.max(0, height - (MIN_MESSAGE_ROWS + BORDER * 2) - fixedBottom);
        int dockRows = allocateRows(panelRows(panelIn(visible, UiRegion.DOCK)), regionLimit, panelBudget);
        int topRows = allocateRows(panelRows(panelIn(visible, UiRegion.TOP)), regionLimit,
                panelBudget - dockRows);
        int bottomRows = fixedBottom + dockRows;
        int messageRows = Math.max(MIN_MESSAGE_ROWS, height - BORDER * 2 - bottomRows - topRows);

        Sidebars sidebars = sidebarsOf(width, visible);
        return new ChatLayout(width, bottomRows, topRows, sidebars.messageWidth, messageRows,
                sidebars.left, sidebars.right, dockRows, sidebars.rightDropped);
    }

    /**
     * 在一个纵向区域的可用预算里分配行数。
     * <p>
     * <b>为什么需要这个预算</b>：{@code DOCK} / {@code TOP} 的高度由内容决定
     * （{@code min(内容行, 8) + 边框}），不像侧栏那样跟着消息区自动变矮。于是「浮层 + 两个满面板 +
     * 输入区 + 状态栏」在矮终端上会超过总高，而 {@code messageRows} 的 {@code max(下限)} 只是把账本
     * 撑成自相矛盾的数字——框架该占几行还是几行，现场表现是底部被裁掉（审批框的键位提示或输入框
     * 看不见）。让它们<b>变矮</b>而不是让整帧溢出，与横向「两栏各保下限」是同一条原则。
     * <p>
     * 分配按优先级先到先得，不按比例：{@code DOCK} 是工作面板实际落的地方（待办的回退落位、
     * 子代理面板），{@code TOP} 目前没有官方插件在用，因此预算不够时先矮 {@code TOP}。
     * 分到的可能少于想要的——渲染时由 {@code UiRender.toVisualLines} 截成「… 还有 N 行」；
     * 但分不到 {@link #MIN_PANEL_ROWS} 就<b>归零</b>：只剩边框的面板没有意义，
     * 而 {@link ChatShell} 会据 0 行不渲染它。
     *
     * @param desiredRows 该区域按内容想要的行数（含边框）
     * @param regionLimit 单区域上限
     * @param budget      本次可用的行数，可为负
     * @return 分配到的行数；不占位时为 0
     */
    private static int allocateRows(int desiredRows, int regionLimit, int budget) {
        int wanted = Math.min(desiredRows, regionLimit);
        if (wanted <= 0 || budget < MIN_PANEL_ROWS) {
            return 0;
        }
        return Math.min(wanted, budget);
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
     * <p>
     * <b>合计超限时优先让两栏共存，而不是直接舍掉右栏</b>：单侧上限（{@code W/4}）与合计上限
     * （{@code W/3}）之间只差 {@code W/12}，因此左栏一旦接近它自己的上限，留给右栏的余量就只剩
     * {@code W/12}——而右栏最小要 {@link #SIDEBAR_MIN_WIDTH} 列，于是 {@code W < 240} 时右栏
     * 必然消失，<b>无论终端多宽</b>（202 列照撞）。现场症状是「待办一出现，右栏那块常驻观测面板
     * 就不见了」：待办条目变长（长文本、卡住时带原因、行尾的认领者）正是把左栏顶到上限的原因。
     * <p>
     * 现在的做法：超限时两栏各不低于 {@link #SIDEBAR_MIN_WIDTH}，在此前提下先满足右栏、余下给左栏；
     * 连两个下限都放不下（{@code W/3 < 2 × 下限}，即 {@code W < 120}）才保留「舍右栏、保左栏」的旧取舍。
     * <p>
     * <b>为什么不改成调小单侧上限（例如 {@code W/6}）</b>：那是拿「永远压窄侧栏」换「两栏共存」，
     * 代价落在<b>只有一块侧栏</b>的场景上——右栏空着、左栏本可以更宽的时候也被限住。
     * 这里只在真的发生竞争时做分配，单栏场景一行不变。
     *
     * @param width   终端总列数（已保证不小于 1）
     * @param visible 每个区域当前选中的面板，可为 {@code null}
     * @return 宽度分配结果
     */
    private static Sidebars sidebarsOf(int width, Map<UiRegion, OwnedPanel> visible) {
        int limit = width / SIDEBAR_MAX_DIVISOR;
        int budget = width / SIDEBAR_TOTAL_DIVISOR;
        int left = sidebarWidth(panelIn(visible, UiRegion.LEFT), width, limit);
        int right = sidebarWidth(panelIn(visible, UiRegion.RIGHT), width, limit);
        boolean rightDropped = false;
        if (left + right > budget) {
            // 超限只可能发生在两栏都有面板时：单侧上限 W/4 本身就小于合计上限 W/3
            if (width >= minWidthForBothSidebars()) {
                right = Math.min(right, budget - SIDEBAR_MIN_WIDTH);
                left = Math.min(left, budget - right);
            } else {
                // 连两个下限都放不下（很窄的终端）：保留旧取舍——保左栏，它更靠近阅读起点
                right = 0;
                rightDropped = true;
            }
        }
        int messageWidth = width - BORDER * 2 - left - right;
        if (messageWidth < MIN_MESSAGE_WIDTH && right > 0) {
            right = 0;
            rightDropped = true;
            messageWidth = width - BORDER * 2 - left;
        }
        if (messageWidth < MIN_MESSAGE_WIDTH && left > 0) {
            left = 0;
            messageWidth = width - BORDER * 2;
        }
        return new Sidebars(left, right, Math.max(1, messageWidth), rightDropped);
    }

    /**
     * 计算「两个侧栏同时显示」所需的最小终端列数。
     * <p>
     * 由 {@code W/3 ≥ 2 × 下限} 反推出来。它有两个用处：{@link #sidebarsOf} 用它判断该走共存还是
     * 退化成只留左栏，降级日志用它给出可执行的建议——只说「放不下」而说不出「要到多宽才放得下」，
     * 读日志的人还得自己回来翻常量。
     *
     * @return 最小列数
     */
    static int minWidthForBothSidebars() {
        return SIDEBAR_MIN_WIDTH * 2 * SIDEBAR_TOTAL_DIVISOR;
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

    /**
     * 判断本帧是否因为宽度预算放不下两个侧栏下限而舍掉了右栏。
     * <p>
     * <b>为什么把它报出来而不是就地记一条日志</b>：本类每帧被调用一次，就地记就是每秒几十条
     * 一样的 WARN（实测刷到过一万六千条）。而本类又是刻意保持纯函数的（可单测、无状态），
     * 放进「上次报过没有」的可变状态会破坏这个性质，静态标志还会跨会话、跨测试残留。
     * 因此判断留在本类、去重交给外壳（它有帧间状态）。
     *
     * @return 本帧舍掉了右栏返回 {@code true}
     */
    public boolean isRightSidebarDropped() {
        return rightSidebarDropped;
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

        /** 是否因为宽度预算放不下两个侧栏下限而舍掉了右栏（由调用方决定要不要记日志）。 */
        private final boolean rightDropped;

        /**
         * 构造宽度分配结果。
         *
         * @param left        左侧栏宽度
         * @param right       右侧栏宽度
         * @param messageWidth 消息区内容列数
         * @param rightDropped 是否舍掉了右栏
         */
        private Sidebars(int left, int right, int messageWidth, boolean rightDropped) {
            this.left = left;
            this.right = right;
            this.messageWidth = messageWidth;
            this.rightDropped = rightDropped;
        }
    }
}
