package zcd.jellyfish.tui;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import zcd.jellyfish.tui.text.StyledSegment;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.length;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

/**
 * 版式：把「消息区 + 输入区 + 状态栏」拼成一帧。
 * <p>
 * <b>结构（§2.3）</b>：
 * <pre>
 * ┌ 消息区（圆角边框，内容为单 RichText）┐
 * │  ❯ 用户消息                          │
 * │  ⏺ jellyfish ...                     │
 * └──────────────────────────────────────┘
 * ┌ 输入框（圆角边框，多行）              ┐
 * └──────────────────────────────────────┘
 *  agent · provider/model · mode · token
 * </pre>
 * <b>为什么消息区是「一个 RichText」而不是「每条消息一个元素」</b>：冒烟实测布局容器的子元素
 * 在 120～180 个处出现断崖（38ms/帧 → &gt;3000ms/帧），而单个 {@code richText} 承载 3000 行
 * 仅需约 1.96ms/帧。因此消息区必须把整屏内容压成<b>一个</b>元素——这个约束直接决定了
 * {@link TranscriptProjector} 的输出形态是「视觉行列表」而不是「元素列表」。
 * <p>
 * <b>高度账本为什么必须在外面算</b>：消息区要看多少行取决于底部占多少行，而底部高度由输入框内容决定。
 * {@code DockElement} 只能表达「底部占多少」，算不出「中间剩多少」，因此行列数由
 * {@link #messageAreaWidth(int)} 与 {@link #messageAreaRows(int, int)} 显式给出，
 * 交给 {@link TuiApp} 用它做投影切片。这两个方法与 {@link ChatInputView#panelRows()} 是同一账本的两端，
 * 改动其一必须同时改另一处。
 *
 * @author zcd
 */
public final class ChatShell {

    /** 状态栏占用行数。 */
    static final int STATUS_ROWS = 1;

    /** 面板边框在每侧占用的列数/行数。 */
    static final int BORDER_SIZE = 1;

    /** 消息区标题。 */
    private static final String MESSAGE_TITLE = " jellyfish ";

    /** 输入区标题。 */
    private static final String INPUT_TITLE = " 输入 ";

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
     * 计算消息区内容宽度。
     *
     * @param terminalWidth 终端总列数
     * @return 内容列数，最小为 1
     */
    public static int messageAreaWidth(int terminalWidth) {
        return Math.max(1, terminalWidth - BORDER_SIZE * 2);
    }

    /**
     * 计算消息区内容行数。
     *
     * @param terminalHeight   终端总行数
     * @param inputPanelRows   输入面板占用行数（含边框）
     * @return 内容行数，最小为 1
     */
    public static int messageAreaRows(int terminalHeight, int inputPanelRows) {
        int chrome = STATUS_ROWS + inputPanelRows + BORDER_SIZE * 2;
        return Math.max(1, terminalHeight - chrome);
    }

    /**
     * 渲染一帧。
     *
     * @param view       本帧消息区窗口，不可为 {@code null}
     * @param title      消息区标题文本，可为 {@code null}
     * @param statusLine 状态栏文本，可为 {@code null}
     * @return 根元素，保证非 {@code null}
     */
    public Element render(ChatState.View view, String title, String statusLine) {
        int inputPanelRows = input.panelRows();
        Element body = richText(Text.from(toLines(view.getLines(), null)));
        Element messagePanel = title == null || title.isEmpty()
                ? panel(body).rounded()
                : panel(title, body).rounded();
        Element bottom = column(
                panel(INPUT_TITLE, input).rounded(),
                text(statusLine == null ? "" : statusLine).dim());
        return dock()
                .bottom(bottom, length(inputPanelRows + STATUS_ROWS))
                .center(messagePanel);
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
