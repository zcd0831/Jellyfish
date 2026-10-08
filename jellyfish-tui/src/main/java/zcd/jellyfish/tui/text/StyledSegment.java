package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;
import zcd.jellyfish.infra.support.ControlChars;

import java.util.Objects;

/**
 * 一段带样式的文本：投影结果的最小组成单位。
 * <p>
 * <b>为什么不直接用 TamboUI 的 {@code Span}</b>：投影（会话消息 → 屏幕行）是本外壳里最需要单测的部分，
 * 而 {@code Span} 与渲染引擎绑在一起。用一层自有值对象承载「文本 + 样式」，投影就能在没有任何
 * 终端环境的情况下被断言，转换到 {@code Span} 只发生在渲染入口的一处。
 * <p>
 * <b>样式为什么不允许为 {@code null}</b>：冒烟实测 {@code Span.styled(text, null)} 会在
 * {@code Span.computeHashCode} 里抛 {@link NullPointerException}。把「无样式」统一表达为
 * {@link Style#EMPTY} 后，这个坑在类型层面就不可能再踩到。
 * <p>
 * <b>构造即过滤控制字符，这是屏幕文本的唯一收口</b>：进屏幕的文本有两个来源——模型与工具输出
 * （不可信）、以及插件贡献与文件名候选（同样不可信，插件是第三方代码，文件名来自文件系统）。
 * 终端把 {@code ESC} 当控制序列引导符：{@code ESC[2J} 能清屏、{@code ESC[H} 能把光标挪回去覆盖界面。
 * 逐个渲染器去过滤的做法漏一处就漏一条路（面板、状态栏、工具名、文件名候选都曾各自漏掉），
 * 因此把过滤放在所有渲染路径的汇合点——投影出的每一段文本都要经过本类。
 * <p>
 * 过滤是<b>幂等</b>的，所以上游已经滤过的文本（markdown 渲染、审批浮层、工具输出）再过一次没有副作用；
 * 反过来，将来新增的渲染路径什么都不用做就自动被覆盖。
 *
 * @author zcd
 */
public final class StyledSegment {

    /** 无样式的空段，用于占位与空行。 */
    public static final StyledSegment EMPTY = new StyledSegment("", Style.EMPTY);

    /** 文本内容，构造时已滤掉控制字符。 */
    private final String text;

    /** 文本样式，保证非 {@code null}。 */
    private final Style style;

    /**
     * 构造文本段。
     *
     * @param text  文本内容，不可为 {@code null}；控制字符会被滤掉
     * @param style 文本样式，不可为 {@code null}；无样式请传 {@link Style#EMPTY}
     */
    public StyledSegment(String text, Style style) {
        this.text = ControlChars.strip(Objects.requireNonNull(text, "text must not be null"));
        this.style = Objects.requireNonNull(style, "style must not be null");
    }

    /**
     * 构造无样式文本段。
     *
     * @param text 文本内容，不可为 {@code null}
     * @return 文本段
     */
    public static StyledSegment of(String text) {
        return new StyledSegment(text, Style.EMPTY);
    }

    /**
     * 获取文本内容。
     *
     * @return 文本内容
     */
    public String getText() {
        return text;
    }

    /**
     * 获取文本样式。
     *
     * @return 文本样式，保证非 {@code null}
     */
    public Style getStyle() {
        return style;
    }

    /**
     * 获取本段的显示宽度（列数）。
     *
     * @return 列数
     */
    public int width() {
        return DisplayWidth.of(text);
    }

    /**
     * 判断本段是否为空文本。
     *
     * @return 空返回 {@code true}
     */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return text;
    }
}
