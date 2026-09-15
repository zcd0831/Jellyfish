package zcd.jellyfish.api.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个逻辑行：从左到右拼接的若干 {@link UiSegment}。
 * <p>
 * <b>是「逻辑行」而不是「屏幕行」</b>：插件只知道内容，不知道外壳给的宽度（它看不到终端尺寸），
 * 因此折行必然由外壳完成——这一行最终占几行屏幕是外壳的事。插件的责任只有「一行 = 一个要读的念头」。
 * <p>
 * <b>为什么不给 {@code width()}</b>：显示宽度是「中日韩字符占两列」这类终端知识，属于外壳；
 * api 零依赖也算不了它。插件因此<b>不该</b>用对齐与否来做版式设计。
 * <p>
 * 构造时会丢弃空文本段（渲染上不可见，留着只会让外壳多一次样式切换）；
 * 全部为空时等价于 {@link #EMPTY}。不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiLine {

    /** 空行：没有任何可见内容。 */
    public static final UiLine EMPTY = new UiLine(Collections.<UiSegment>emptyList());

    /** 组成本行的文本段，按顺序拼接。 */
    private final List<UiSegment> segments;

    /**
     * 构造行。
     *
     * @param segments 文本段列表，可为 {@code null} 或空（等价于 {@link #EMPTY}）
     */
    public UiLine(List<UiSegment> segments) {
        this.segments = Collections.unmodifiableList(nonEmptyOf(segments));
    }

    /**
     * 构造纯文本行。
     *
     * @param text 文本内容，可为 {@code null}
     * @return 行，保证非 {@code null}
     */
    public static UiLine of(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return new UiLine(Collections.singletonList(UiSegment.of(text)));
    }

    /**
     * 构造由若干段拼接的行。
     *
     * @param segments 文本段，可为 {@code null}
     * @return 行，保证非 {@code null}
     */
    public static UiLine of(UiSegment... segments) {
        if (segments == null || segments.length == 0) {
            return EMPTY;
        }
        List<UiSegment> list = new ArrayList<UiSegment>(segments.length);
        Collections.addAll(list, segments);
        return new UiLine(list);
    }

    /**
     * 获取组成本行的文本段。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<UiSegment> getSegments() {
        return segments;
    }

    /**
     * 获取本行的纯文本内容（丢弃样式，用于外壳折行前的判断与调试）。
     *
     * @return 纯文本，保证非 {@code null}
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (UiSegment segment : segments) {
            sb.append(segment.getText());
        }
        return sb.toString();
    }

    /**
     * 判断本行是否没有任何可见内容。
     *
     * @return 空行返回 {@code true}
     */
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /**
     * 复制出非空的文本段。
     *
     * @param segments 文本段列表，可为 {@code null}
     * @return 新的段列表，保证非 {@code null}
     */
    private static List<UiSegment> nonEmptyOf(List<UiSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Collections.emptyList();
        }
        List<UiSegment> result = new ArrayList<UiSegment>(segments.size());
        for (UiSegment segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            result.add(segment);
        }
        return result;
    }

    @Override
    public String toString() {
        return "UiLine{" + text() + '}';
    }
}
