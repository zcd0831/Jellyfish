package zcd.jellyfish.tui;

import zcd.jellyfish.tui.text.VisualLine;

import java.util.Collections;
import java.util.List;

/**
 * 输入框上方的浮层面板内容：带标题的若干视觉行。
 * <p>
 * <b>为什么要有这一层</b>：T8 的补全面板与 T9 的二级选择页占用的是同一个位置（输入框上方），
 * 高度也走同一份账本（{@link ChatShell#messageAreaRows}）。若让 {@code ChatShell} 认识两种面板，
 * 每加一种面板就要改一次版式；抽象成「标题 + 行」后，版式只认识浮层，面板种类由外壳决定。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class Overlay {

    /** 空浮层：不显示。 */
    private static final Overlay NONE = new Overlay(null, Collections.<VisualLine>emptyList());

    /** 面板标题，可为 {@code null}。 */
    private final String title;

    /** 面板内容行，保证非 {@code null}。 */
    private final List<VisualLine> lines;

    /**
     * 构造浮层。
     *
     * @param title 面板标题，可为 {@code null}
     * @param lines 内容行，可为 {@code null} 或空（等价于空浮层）
     */
    public Overlay(String title, List<VisualLine> lines) {
        this.title = title;
        this.lines = lines == null || lines.isEmpty()
                ? Collections.<VisualLine>emptyList()
                : Collections.unmodifiableList(lines);
    }

    /**
     * 获取空浮层。
     *
     * @return 不显示任何内容、行数为 0 的浮层
     */
    public static Overlay none() {
        return NONE;
    }

    /**
     * 判断浮层是否为空。
     *
     * @return 无内容行返回 {@code true}
     */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 获取面板标题。
     *
     * @return 面板标题，可为 {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取面板内容行。
     *
     * @return 不可变内容行列表，保证非 {@code null}
     */
    public List<VisualLine> getLines() {
        return lines;
    }
}
