package zcd.jellyfish.tui;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.DockElement;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

/**
 * 版式：把「消息区 + 各面板区域 + 输入区 + 状态栏」拼成一帧。
 * <p>
 * <b>结构（从外到内）</b>：{@code DockElement} 五边停靠，顶部与左右放插件面板，
 * 底部是固定顺序的纵列，中心是消息区：
 * <pre>
 * ┌ 顶部面板（TOP，可无）────────────────┐
 * │ ┌ 左栏 ┐ ┌ 消息区（圆角边框）──────┐ │
 * │ │ LEFT │ │  ❯ 用户消息            │ │
 * │ │      │ │  ⏺ jellyfish ...       │ │
 * │ └──────┘ └────────────────────────┘ │
 * │          ┌ 右栏 ┐                    │
 * │          │ RIGHT│                    │
 * │          └──────┘                    │
 * ├ 停靠面板（DOCK，可无）───────────────┤
 * ├ 浮层面板（补全 / 选择页，可无）──────┤
 * ├ 输入框（圆角边框，多行）─────────────┤
 * └ 状态栏（一行）───────────────────────┘
 * </pre>
 * <b>底部各段的顺序是固定的</b>：{@code [停靠面板, 浮层?, 输入区, 状态栏]}。停靠面板在浮层之上，
 * 因为浮层与输入内容强相关、必须贴着输入框；状态栏永远在最后一行，它是一行扫读指标。
 * <p>
 * <b>为什么消息区是「一个 RichText」而不是「每条消息一个元素」</b>：冒烟实测布局容器的子元素
 * 在 120～180 个处出现断崖（38ms/帧 → &gt;3000ms/帧），而单个 {@code richText} 承载 3000 行
 * 仅需约 1.96ms/帧。因此消息区必须把整屏内容压成<b>一个</b>元素——这个约束直接决定了
 * {@link TranscriptProjector} 的输出形态是「视觉行列表」而不是「元素列表」。
 * <p>
 * <b>为什么高度账本在外面算（{@link ChatLayout}）</b>：消息区要看多少行取决于底部与顶部占多少行，
 * 而底部高度又由输入框内容与面板行数决定。{@code DockElement} 只能表达「这里占多少」，
 * 算不出「中间剩多少」，因此行列数必须由账本显式给出，交给 {@link TuiApp} 做投影切片。
 * 本类只负责把账本的结论翻译成约束，<b>绝不自行推断尺寸</b>——账本一旦有两处口径就会错位。
 * <p>
 * <b>所有约束一律用 {@code length(n)}</b>：固定值下框架分配的 {@code center} 尺寸与账本算的完全一致；
 * 用 {@code percent} / {@code fill} 就要复刻框架的取整规则，迟早对不上。
 *
 * @author zcd
 */
public final class ChatShell {

    /** 状态栏占用行数。 */
    static final int STATUS_ROWS = 1;

    /** 面板边框在每侧占用的列数/行数。 */
    static final int BORDER_SIZE = 1;

    /** 输入区标题。 */
    private static final String INPUT_TITLE = " 输入 ";

    /** 补全面板标题。 */
    private static final String COMPLETION_TITLE = " 命令 ";

    /** 输入区视图。 */
    private final ChatInputView input;

    /**
     * 构造版式。
     *
     * @param input 输入区视图，不可为 {@code null}
     */
    public ChatShell(ChatInputView input) {
        this.input = input;
    }

    /**
     * 计算浮层面板占用行数。
     * <p>
     * 这是高度账本的一环：{@link ChatLayout#compute} 必须把它的结果算进去，
     * 否则面板出现时会把消息区内容顶掉一行。
     *
     * @param overlay 浮层内容，可为 {@code null}
     * @return 面板行数；没有内容时为 0
     */
    public static int overlayRows(Overlay overlay) {
        return overlay == null || overlay.isEmpty()
                ? 0
                : overlay.getLines().size() + BORDER_SIZE * 2;
    }

    /**
     * 构造补全面板的浮层。
     *
     * @param completionLines 补全内容行，可为 {@code null} 或空
     * @return 浮层；无内容时返回空浮层
     */
    public static Overlay completionOverlay(List<VisualLine> completionLines) {
        if (completionLines == null || completionLines.isEmpty()) {
            return Overlay.none();
        }
        return new Overlay(COMPLETION_TITLE, completionLines);
    }

    /**
     * 计算本帧真正要显示的面板。
     * <p>
     * <b>模态浮层打开时面板区整体让位</b>：浮层（补全面板 / 二级选择页）是「正在输入、正在挑参数」
     * 的强交互，和常驻面板同屏只会把焦点搞散，而它就在输入框上方、与面板根本不重叠——
     * 所以让位是主动的，不是重叠导致的。
     * <p>
     * <b>只影响这一帧的显示</b>：传进来的面板集合并不会被改写，外壳的落位状态也不变，
     * 因此关掉浮层立刻恢复，不需要重新向插件收集（对应「缓存不清」）。
     *
     * @param declared 外壳当前选中的面板，可为 {@code null}
     * @param overlay  本帧浮层，可为 {@code null}
     * @return 要显示的面板；浮层有内容时为空映射
     */
    public static Map<UiRegion, OwnedPanel> visiblePanels(Map<UiRegion, OwnedPanel> declared, Overlay overlay) {
        if (overlayRows(overlay) > 0) {
            return Collections.emptyMap();
        }
        return declared == null ? Collections.<UiRegion, OwnedPanel>emptyMap() : declared;
    }

    /**
     * 渲染一帧。
     *
     * @param view       本帧消息区窗口，不可为 {@code null}
     * @param title      消息区标题文本，可为 {@code null}
     * @param statusLine 状态栏文本，可为 {@code null}
     * @param overlay    输入框上方的浮层面板，可为 {@code null} 或空（不显示）
     * @param panels     每个区域当前选中的插件面板，可为 {@code null} 或空
     * @param layout     本帧版式账本，不可为 {@code null}
     * @return 根元素，保证非 {@code null}
     */
    public Element render(ChatState.View view, String title, String statusLine, Overlay overlay,
                          Map<UiRegion, OwnedPanel> panels, ChatLayout layout) {
        Element messagePanel = messagePanel(view, title);
        DockElement root = dock();

        Element top = panelElement(panels, UiRegion.TOP,
                contentWidth(layout.getTerminalWidth()), layout.getTopRows() - BORDER_SIZE * 2);
        if (top != null) {
            root.top(top, length(layout.getTopRows()));
        }
        Element left = panelElement(panels, UiRegion.LEFT,
                layout.getLeftWidth() - BORDER_SIZE * 2, layout.getMessageRows());
        if (left != null) {
            root.left(left, length(layout.getLeftWidth()));
        }
        Element right = panelElement(panels, UiRegion.RIGHT,
                layout.getRightWidth() - BORDER_SIZE * 2, layout.getMessageRows());
        if (right != null) {
            root.right(right, length(layout.getRightWidth()));
        }
        root.bottom(bottomParts(panels, overlay, statusLine, layout), length(layout.getBottomRows()));
        return root.center(messagePanel);
    }

    /**
     * 构造消息区元素。
     *
     * @param view  本帧消息区窗口
     * @param title 标题文本，可为 {@code null}
     * @return 面板元素
     */
    private static Element messagePanel(ChatState.View view, String title) {
        // 消息区内容必须是一个 RichText：见类注释的性能实测
        Element body = richText(Text.from(toLines(view.getLines(), null)));
        return title == null || title.isEmpty() ? panel(body).rounded() : panel(title, body).rounded();
    }

    /**
     * 构造底部纵列。
     * <p>
     * 顺序固定为 {@code [停靠面板, 浮层?, 输入区, 状态栏]}：停靠面板在上，浮层贴着输入框，
     * 状态栏永远压在最后一行。
     *
     * @param panels     区域面板
     * @param overlay    浮层，可为 {@code null} 或空
     * @param statusLine 状态栏文本，可为 {@code null}
     * @param layout     版式账本
     * @return 底部元素
     */
    private Element bottomParts(Map<UiRegion, OwnedPanel> panels, Overlay overlay,
                                String statusLine, ChatLayout layout) {
        List<Element> parts = new ArrayList<Element>(4);
        Element dockPanel = panelElement(panels, UiRegion.DOCK,
                contentWidth(layout.getTerminalWidth()), layout.getDockRows() - BORDER_SIZE * 2);
        if (dockPanel != null) {
            parts.add(dockPanel);
        }
        if (overlayRows(overlay) > 0) {
            // 浮层面板放在输入框上方：它和输入内容强相关，贴在一起才不会看起来像消息区的尾行
            Element overlayBody = richText(Text.from(toLines(overlay.getLines(), null)));
            String overlayTitle = overlay.getTitle();
            parts.add(overlayTitle == null || overlayTitle.isEmpty()
                    ? panel(overlayBody).rounded()
                    : panel(overlayTitle, overlayBody).rounded());
        }
        parts.add(panel(INPUT_TITLE, input).rounded());
        parts.add(text(statusLine == null ? "" : statusLine).dim());
        return column(parts.toArray(new Element[0]));
    }

    /**
     * 计算整宽面板（{@code DOCK} / {@code TOP}）的内容列数。
     *
     * @param terminalWidth 终端总列数
     * @return 内容列数，最小为 1
     */
    private static int contentWidth(int terminalWidth) {
        return Math.max(1, terminalWidth - BORDER_SIZE * 2);
    }

    /**
     * 构造一个区域的插件面板元素。
     * <p>
     * 折行宽度与行数上限都取自账本：<b>插件无权控制尺寸</b>，它给多少内容都不会撑坏版式。
     * 内容折行后若超过分配的高度，由 {@link UiRender#toVisualLines} 截断并留一行提示。
     *
     * @param panels       区域面板，可为 {@code null}
     * @param region       区域
     * @param contentWidth 可用内容列数
     * @param maxRows      可用内容行数
     * @return 面板元素；该区域没有面板或内容为空时返回 {@code null}
     */
    private static Element panelElement(Map<UiRegion, OwnedPanel> panels, UiRegion region,
                                        int contentWidth, int maxRows) {
        if (panels == null) {
            return null;
        }
        OwnedPanel panel = panels.get(region);
        if (panel == null) {
            return null;
        }
        PanelContribution contribution = panel.getContribution();
        List<VisualLine> lines = UiRender.toVisualLines(contribution, contentWidth, maxRows);
        if (lines.isEmpty()) {
            return null;
        }
        Element body = richText(Text.from(toLines(lines, null)));
        String title = contribution.getTitle();
        return title == null || title.trim().isEmpty()
                ? panel(body).rounded()
                : panel(" " + title.trim() + " ", body).rounded();
    }

    /**
     * 把视觉行转换成 TamboUI 的行序列。
     * <p>
     * 这是自有文本模型与渲染引擎之间<b>唯一</b>的转换点：投影与滚动都建立在自有类型上，
     * 因此它们可以脱离终端单测；只有到这里才需要认识 {@code Line} / {@code Span}。
     *
     * @param lines 视觉行
     * @param hint  末尾提示行，可为 {@code null}
     * @return 行序列，保证非 {@code null}
     */
    private static List<Line> toLines(List<VisualLine> lines, String hint) {
        List<Line> result = new ArrayList<Line>(lines.size() + 1);
        for (VisualLine line : lines) {
            result.add(toLine(line));
        }
        if (hint != null && !hint.isEmpty()) {
            result.add(Line.from(Span.raw(hint)));
        }
        return result;
    }

    /**
     * 把单个视觉行转换成 TamboUI 的行。
     *
     * @param line 视觉行
     * @return 行，保证非 {@code null}
     */
    private static Line toLine(VisualLine line) {
        List<Span> spans = new ArrayList<Span>(line.getSegments().size());
        for (StyledSegment segment : line.getSegments()) {
            // 空样式用 Style.EMPTY 而非 null：Span.styled 对 null 会 NPE（冒烟实测）
            spans.add(Span.styled(segment.getText(), segment.getStyle()));
        }
        return spans.isEmpty() ? Line.empty() : Line.from(spans);
    }
}
