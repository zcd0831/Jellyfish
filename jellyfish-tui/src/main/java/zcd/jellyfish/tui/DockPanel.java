package zcd.jellyfish.tui;

import zcd.jellyfish.tui.text.VisualLine;

import java.util.Collections;
import java.util.List;

/**
 * 常驻面板：带标题的若干视觉行。
 * <p>
 * <b>为什么与 {@link Overlay} 分开定义</b>：两者结构一样，语义与账本完全不同——
 * {@code Overlay} 是<b>模态浮层</b>（补全面板、二级选择页），它一出现就顶掉消息区若干行，
 * 生命周期由输入状态决定；{@code DockPanel} 是<b>常驻面板</b>（插件贡献），
 * 它由用户用 {@code /ui} 决定显示与否，生命周期与输入无关。
 * 共用一个类型会让「模态浮层账本」与「常驻面板账本」在各处的判断条件里混成一套，
 * 而它们的失效触发源、开销与可见性规则都不一样。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class DockPanel {

    /** 空面板：不显示。 */
    private static final DockPanel NONE = new DockPanel(null, Collections.<VisualLine>emptyList());

    /** 面板标题，可为 {@code null}。 */
    private final String title;

    /** 面板内容行，保证非 {@code null}。 */
    private final List<VisualLine> lines;

    /**
     * 构造面板。
     *
     * @param title 面板标题，可为 {@code null}
     * @param lines 内容行，可为 {@code null} 或空（等价于空面板）
     */
    public DockPanel(String title, List<VisualLine> lines) {
        this.title = title;
        this.lines = lines == null || lines.isEmpty()
                ? Collections.<VisualLine>emptyList()
                : Collections.unmodifiableList(lines);
    }

    /**
     * 获取空面板。
     *
     * @return 不显示任何内容、行数为 0 的面板
     */
    public static DockPanel none() {
        return NONE;
    }

    /**
     * 判断面板是否为空。
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
