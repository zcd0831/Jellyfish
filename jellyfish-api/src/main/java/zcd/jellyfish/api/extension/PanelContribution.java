package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 面板贡献结果：插件想在界面上常驻显示的一块内容。
 * <p>
 * <b>形态是「独占型」</b>：一块区域同一时刻只显示一个面板——面板带边框、占多行，
 * 几块叠在一起只会互相挤坏（这是与状态栏片段「拼接共存」的根本差别，不是额外规则）。
 * 因此多个插件抢同一区域时，由用户用 {@code /ui} 切换；默认只显示 {@code order} 最小的那个。
 * <p>
 * <b>插件无权控制尺寸</b>：{@link #getLines()} 只是「我要显示这些行」，行数上限、宽度折行、
 * 超长截断全部由外壳决定。否则一个插件就能用自己的内容把消息区挤没。
 * <p>
 * <b>落位是建议不是命令</b>：{@link #getPreferredRegion()} 见 {@link UiRegion} 的注释——
 * 外壳可以忽略它，插件不得假设自己一定在那里，也不得假设一定显示。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PanelContribution {

    /** 空贡献：这次没有要显示的面板。 */
    private static final PanelContribution EMPTY =
            new PanelContribution(null, Collections.<UiLine>emptyList(), null);

    /** 面板标题，可为 {@code null}（无标题）。 */
    private final String title;

    /** 面板内容行，保证非 {@code null}。 */
    private final List<UiLine> lines;

    /** 建议落位区域，可为 {@code null}（走外壳默认区域）。 */
    private final UiRegion preferredRegion;

    /**
     * 构造贡献。
     *
     * @param title           面板标题，可为 {@code null}
     * @param lines           内容行，可为 {@code null} 或空（等价于空贡献）
     * @param preferredRegion 建议落位区域，可为 {@code null}
     */
    public PanelContribution(String title, List<UiLine> lines, UiRegion preferredRegion) {
        this.title = title;
        this.lines = lines == null || lines.isEmpty()
                ? Collections.<UiLine>emptyList()
                : Collections.unmodifiableList(new ArrayList<UiLine>(lines));
        this.preferredRegion = preferredRegion;
    }

    /**
     * 构造贡献。
     *
     * @param title           面板标题，可为 {@code null}
     * @param lines           内容行，可为 {@code null} 或空（等价于 {@link #empty()}）
     * @param preferredRegion 建议落位区域，可为 {@code null}
     * @return 贡献结果，保证非 {@code null}
     */
    public static PanelContribution of(String title, List<UiLine> lines, UiRegion preferredRegion) {
        if (lines == null || lines.isEmpty()) {
            return EMPTY;
        }
        return new PanelContribution(title, lines, preferredRegion);
    }

    /**
     * 构造空贡献。
     *
     * @return 没有内容的面板贡献
     */
    public static PanelContribution empty() {
        return EMPTY;
    }

    /**
     * 获取面板标题。
     *
     * @return 面板标题，无标题时为 {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取面板内容行。
     *
     * @return 不可变内容行列表，保证非 {@code null}
     */
    public List<UiLine> getLines() {
        return lines;
    }

    /**
     * 获取建议落位区域。
     *
     * @return 建议区域，未指定时为 {@code null}
     */
    public UiRegion getPreferredRegion() {
        return preferredRegion;
    }

    /**
     * 判断是否没有贡献内容。
     *
     * @return 无内容返回 {@code true}
     */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public String toString() {
        return "PanelContribution{title=" + title + ", lines=" + lines.size()
                + ", preferredRegion=" + preferredRegion + '}';
    }
}
