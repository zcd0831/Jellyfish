package zcd.jellyfish.tui.text;

import dev.tamboui.style.Style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 换行器：把「一条逻辑行（前缀 + 正文）」展开成若干视觉行。
 * <p>
 * <b>为什么换行要自己做</b>：滚动按视觉行计数（见 {@link VisualLine}），而视觉行的数量只有在我们
 * 自己完成换行之后才确定。若把换行交给渲染引擎，滚动偏移就无法和屏幕内容对齐——
 * 长消息会让「向上翻 N 行」翻出不一致的距离。
 * <p>
 * <b>前缀不参与换行</b>：角色前缀（{@code ❯ } / {@code ⏺ jellyfish} / {@code ⎿ }）是行首标记，
 * 正文按「总宽 − 前缀宽」换行；续行补等宽空白缩进，使整条消息在视觉上仍然归属同一个层级。
 * 这条规则正是 §2.3「缩进分级」的实现方式。
 * <p>
 * <b>换行策略</b>：贪心逐码点填充，不切词。理由：中文没有词间空格，按词换行对中文无效；
 * 而对英文路径 / URL 这类长串，中途截断比整词推到下一行更省纵向空间，也更接近终端的常规表现。
 * 行首的空格会被丢弃（避免续行以空格开头造成的锯齿）。
 *
 * @author zcd
 */
public final class LineWrapper {

    /** 正文至少要留出的列数，防止前缀过宽时把正文挤没。 */
    private static final int MIN_BODY_WIDTH = 1;

    private LineWrapper() {
    }

    /**
     * 按宽度展开一条逻辑行。
     *
     * @param prefix 行首前缀，不可为 {@code null}；不参与换行
     * @param body   正文样式段，不可为 {@code null}；其中的 {@code '\n'} 触发强制换行
     * @param width  可用总列数；小于 1 时按 1 处理
     * @return 视觉行列表，至少一个元素（正文为空时返回仅含前缀的一行）
     */
    public static List<VisualLine> wrap(StyledSegment prefix, List<StyledSegment> body, int width) {
        int totalWidth = Math.max(1, width);
        int prefixWidth = Math.min(prefix.width(), totalWidth - MIN_BODY_WIDTH);
        if (prefixWidth < 0) {
            prefixWidth = 0;
        }
        int available = Math.max(MIN_BODY_WIDTH, totalWidth - prefixWidth);
        StyledSegment indent = new StyledSegment(spaces(prefixWidth), Style.EMPTY);

        List<VisualLine> lines = new ArrayList<VisualLine>();
        Accumulator acc = new Accumulator();
        boolean first = true;
        for (StyledSegment segment : body) {
            String text = segment.getText();
            int i = 0;
            int len = text.length();
            while (i < len) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    flush(lines, acc, prefix, indent, first);
                    first = false;
                    continue;
                }
                int codePointWidth = DisplayWidth.ofCodePoint(codePoint);
                boolean isSpace = codePoint == ' ';
                if (codePointWidth > 0 && !acc.fits(codePointWidth, available)) {
                    flush(lines, acc, prefix, indent, first);
                    first = false;
                    // 换行处的空格不带到下一行行首，否则续行会出现锯齿缩进
                    if (isSpace) {
                        continue;
                    }
                }
                // 行首空格直接丢弃
                if (isSpace && acc.isEmpty()) {
                    continue;
                }
                acc.append(codePoint, segment.getStyle(), codePointWidth);
            }
        }
        flush(lines, acc, prefix, indent, first);
        return lines;
    }

    /**
     * 按宽度展开一条纯文本逻辑行。
     *
     * @param prefix 行首前缀文本，不可为 {@code null}；使用无样式
     * @param body   正文文本，不可为 {@code null}
     * @param width  可用总列数
     * @return 视觉行列表
     */
    public static List<VisualLine> wrap(String prefix, String body, int width) {
        return wrap(StyledSegment.of(prefix),
                Collections.singletonList(StyledSegment.of(body)), width);
    }

    /**
     * 按宽度展开一条逻辑行，正文使用无样式。
     *
     * @param prefix 行首前缀，不可为 {@code null}；不参与换行
     * @param body   正文文本，不可为 {@code null}
     * @param width  可用总列数
     * @return 视觉行列表
     */
    public static List<VisualLine> wrap(StyledSegment prefix, String body, int width) {
        return wrap(prefix, Collections.singletonList(StyledSegment.of(body)), width);
    }

    /**
     * 把当前累积的正文收尾成一行并清空累积器。
     *
     * @param lines  输出列表
     * @param acc    累积器
     * @param prefix 首行前缀
     * @param indent 续行缩进
     * @param first  是否为第一条视觉行（决定用前缀还是缩进）
     */
    private static void flush(List<VisualLine> lines, Accumulator acc, StyledSegment prefix,
                              StyledSegment indent, boolean first) {
        List<StyledSegment> segments = new ArrayList<StyledSegment>();
        segments.add(first ? prefix : indent);
        segments.addAll(acc.drain());
        lines.add(new VisualLine(segments));
    }

    /**
     * 生成指定列数的空白。
     *
     * @param columns 列数
     * @return 空白字符串，列数小于 1 时返回空串
     */
    private static String spaces(int columns) {
        if (columns <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(columns);
        for (int i = 0; i < columns; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * 逐码点累积器：把相邻同一样式的码点合并成一个 {@link StyledSegment}。
     * <p>
     * 合并的意义是让最终交给渲染引擎的样式段数量与「样式切换次数」同阶，而不是与字符数同阶。
     * 一次 3000 行的投影里，这个消息段的差别会直接体现在每帧成本上。
     */
    private static final class Accumulator {

        /** 已完成的样式段。 */
        private final List<StyledSegment> segments = new ArrayList<StyledSegment>();

        /** 正在累积的文本。 */
        private final StringBuilder pending = new StringBuilder();

        /** 正在累积的文本样式；{@code null} 表示尚未开始累积。 */
        private Style pendingStyle;

        /** 本行已占用的列数。 */
        private int used;

        /**
         * 追加一个码点。
         *
         * @param codePoint 码点
         * @param style     样式
         * @param width     该码点占用的列数
         */
        void append(int codePoint, Style style, int width) {
            if (pendingStyle != null && !pendingStyle.equals(style)) {
                sealPending();
            }
            pendingStyle = style;
            pending.appendCodePoint(codePoint);
            used += width;
        }

        /**
         * 判断再追加指定宽度的码点是否仍在本行容量内。
         *
         * @param width     待追加码点的宽度
         * @param available 本行容量
         * @return 放得下返回 {@code true}
         */
        boolean fits(int width, int available) {
            return used + width <= available;
        }

        /**
         * 判断本行是否还没有任何内容。
         *
         * @return 空返回 {@code true}
         */
        boolean isEmpty() {
            return used == 0;
        }

        /**
         * 取出本行全部样式段并重置累积器。
         *
         * @return 样式段列表
         */
        List<StyledSegment> drain() {
            sealPending();
            List<StyledSegment> result = new ArrayList<StyledSegment>(segments);
            segments.clear();
            used = 0;
            return result;
        }

        /**
         * 把正在累积的文本固化成一个样式段。
         */
        private void sealPending() {
            if (pending.length() > 0) {
                segments.add(new StyledSegment(pending.toString(), pendingStyle));
                pending.setLength(0);
            }
            pendingStyle = null;
        }
    }
}
