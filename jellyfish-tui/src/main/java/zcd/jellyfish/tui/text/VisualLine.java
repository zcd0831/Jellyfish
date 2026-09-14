package zcd.jellyfish.tui.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个视觉行：终端上从左到右占满一行的若干样式段。
 * <p>
 * <b>「视觉行」是投影与滚动之间的计量单位</b>：滚动偏移、可见窗口切片、跟随底部判据全部按它计数。
 * 一条逻辑消息可能因为换行展开成多个视觉行，因此不能拿「消息条数」当滚动单位，
 * 否则长消息会让滚动位置与屏幕内容对不上。
 * <p>
 * 与 {@link StyledSegment} 一样，这是自有值对象而非 TamboUI 的 {@code Line}：
 * 让「消息 → 屏幕行」这段逻辑可脱离终端单测。
 *
 * @author zcd
 */
public final class VisualLine {

    /** 空行。 */
    public static final VisualLine EMPTY = new VisualLine(Collections.<StyledSegment>emptyList());

    /** 组成本行的样式段，按顺序拼接。 */
    private final List<StyledSegment> segments;

    /**
     * 构造视觉行。
     *
     * @param segments 样式段列表，不可为 {@code null}；内部会复制为不可变列表
     */
    public VisualLine(List<StyledSegment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<StyledSegment>(segments));
    }

    /**
     * 由单个文本段构造视觉行。
     *
     * @param segment 文本段，不可为 {@code null}
     * @return 视觉行
     */
    public static VisualLine of(StyledSegment segment) {
        return new VisualLine(Collections.singletonList(segment));
    }

    /**
     * 构造纯文本视觉行。
     *
     * @param text 文本内容，不可为 {@code null}
     * @return 视觉行
     */
    public static VisualLine of(String text) {
        return of(StyledSegment.of(text));
    }

    /**
     * 构造由若干样式段拼接的视觉行。
     *
     * @param segments 样式段
     * @return 视觉行
     */
    public static VisualLine of(StyledSegment... segments) {
        List<StyledSegment> list = new ArrayList<StyledSegment>(segments.length);
        Collections.addAll(list, segments);
        return new VisualLine(list);
    }

    /**
     * 获取组成本行的样式段。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    public List<StyledSegment> getSegments() {
        return segments;
    }

    /**
     * 获取本行的纯文本内容（丢弃样式，用于断言与调试）。
     *
     * @return 纯文本，保证非 {@code null}
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (StyledSegment segment : segments) {
            sb.append(segment.getText());
        }
        return sb.toString();
    }

    /**
     * 获取本行的显示宽度（列数）。
     *
     * @return 列数
     */
    public int width() {
        int total = 0;
        for (StyledSegment segment : segments) {
            total += segment.width();
        }
        return total;
    }

    /**
     * 判断本行是否没有任何可见内容。
     *
     * @return 空返回 {@code true}
     */
    public boolean isEmpty() {
        return width() == 0;
    }

    @Override
    public String toString() {
        return text();
    }
}
