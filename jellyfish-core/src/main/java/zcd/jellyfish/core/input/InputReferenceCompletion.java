package zcd.jellyfish.core.input;

import zcd.jellyfish.api.extension.InputReferenceChoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次行内引用补全的结果：要替换的字符区间 + 候选清单。
 * <p>
 * <b>为什么带上替换区间</b>：外壳需要知道「接受候选后该把哪一段换掉」，而那段区间（哪个 {@code @片段}
 * 覆盖了光标）的判定规则属于内核——它与「什么算一个片段」是同一件事。放在内核算一次，
 * 任何外壳都不必重实现，也不会与传给插件的 {@code token} 出现口径差。
 * <p>
 * <b>没有命中时用 {@link #empty()}</b>：{@code marker} 为 {@code null}、区间为 {@code -1}，
 * 外壳据此决定不弹面板。刻意不提供 {@code null} 返回值，免得每个调用点都判空。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputReferenceCompletion {

    /** 未命中：没有引用片段，或没有插件认领该标记。 */
    private static final InputReferenceCompletion EMPTY =
            new InputReferenceCompletion(-1, -1, null, null);

    /** 替换区间起点（含）。 */
    private final int replaceStart;

    /** 替换区间终点（不含）。 */
    private final int replaceEnd;

    /** 命中的标记字符，未命中时为 {@code null}。 */
    private final String marker;

    /** 候选清单，保证非 {@code null}。 */
    private final List<InputReferenceChoice> choices;

    /**
     * 构造补全结果。
     *
     * @param replaceStart 替换起点
     * @param replaceEnd   替换终点
     * @param marker       标记字符，可为 {@code null}
     * @param choices      候选清单，可为 {@code null}
     */
    private InputReferenceCompletion(int replaceStart, int replaceEnd, String marker,
                                     List<InputReferenceChoice> choices) {
        this.replaceStart = replaceStart;
        this.replaceEnd = replaceEnd;
        this.marker = marker;
        this.choices = choices == null || choices.isEmpty()
                ? Collections.<InputReferenceChoice>emptyList()
                : Collections.unmodifiableList(new ArrayList<InputReferenceChoice>(choices));
    }

    /**
     * 获取未命中结果。
     *
     * @return 未命中的补全结果
     */
    public static InputReferenceCompletion empty() {
        return EMPTY;
    }

    /**
     * 构造命中的补全结果。
     *
     * @param replaceStart 替换起点（含）
     * @param replaceEnd   替换终点（不含）
     * @param marker       标记字符，不可为空白
     * @param choices      候选清单，可为 {@code null} 或空（等价「无匹配」，仍会弹面板占位）
     * @return 补全结果，保证非 {@code null}
     */
    public static InputReferenceCompletion of(int replaceStart, int replaceEnd, String marker,
                                              List<InputReferenceChoice> choices) {
        return new InputReferenceCompletion(replaceStart, replaceEnd, marker, choices);
    }

    /**
     * 判断是否命中了一个引用片段。
     *
     * @return 命中返回 {@code true}
     */
    public boolean isPresent() {
        return marker != null;
    }

    /**
     * 获取替换区间起点。
     *
     * @return 起点；未命中时为 {@code -1}
     */
    public int getReplaceStart() {
        return replaceStart;
    }

    /**
     * 获取替换区间终点。
     *
     * @return 终点；未命中时为 {@code -1}
     */
    public int getReplaceEnd() {
        return replaceEnd;
    }

    /**
     * 获取命中的标记字符。
     *
     * @return 标记字符；未命中时为 {@code null}
     */
    public String getMarker() {
        return marker;
    }

    /**
     * 获取候选清单。
     *
     * @return 不可变候选清单，保证非 {@code null}
     */
    public List<InputReferenceChoice> getChoices() {
        return choices;
    }

    @Override
    public String toString() {
        return "InputReferenceCompletion{marker=" + marker + ", choices=" + choices.size() + '}';
    }
}
