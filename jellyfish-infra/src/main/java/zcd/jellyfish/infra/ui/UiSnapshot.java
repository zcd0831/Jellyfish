package zcd.jellyfish.infra.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 UI 贡献收集的结果快照：外壳渲染一帧所需的外部内容。
 * <p>
 * <b>为什么要有这一层值类型</b>：外壳的渲染帧与「向插件收集」是两个时机（收集只在缓存失效时发生），
 * 快照就是两者之间的那份数据。它不可变，因此可以安全地在渲染线程反复读取而不必担心收集过程中变脸。
 * <p>
 * <b>为什么状态栏是「片段列表」而不是拼好的一个字符串</b>：外壳需要按终端宽度<b>从最后一个片段开始
 * 整块丢弃</b>（截断出来的半个片段毫无意义，还会让人以为插件坏了）——片段边界必须留到外壳才知道
 * 可用宽度的那一刻。
 * <p>
 * <b>面板为什么带 owner 而上屏前才落位</b>：面板是独占型资源，落位（哪个插件的面板显示在哪个区域）
 * 依赖用户用 {@code /ui} 做过的选择，而那是外壳的交互状态，不该由「收集」这一步决定。
 * 收集只负责「有哪些面板、各属于谁、按 {@code order} 升序」，顺序由注册表给出，外壳不再排序。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiSnapshot {

    /** 空快照：没有任何插件贡献。 */
    private static final UiSnapshot EMPTY =
            new UiSnapshot(Collections.<String>emptyList(), Collections.<OwnedPanel>emptyList());

    /** 状态栏片段，按注册顺序排列。 */
    private final List<String> statusFragments;

    /** 面板贡献，按 {@code order} 升序排列。 */
    private final List<OwnedPanel> panels;

    /**
     * 构造快照。
     *
     * @param statusFragments 状态栏片段，不可为 {@code null}
     * @param panels          面板贡献，不可为 {@code null}
     */
    private UiSnapshot(List<String> statusFragments, List<OwnedPanel> panels) {
        this.statusFragments = statusFragments;
        this.panels = panels;
    }

    /**
     * 构造空快照。
     *
     * @return 没有任何贡献的快照
     */
    public static UiSnapshot empty() {
        return EMPTY;
    }

    /**
     * 构造快照。
     *
     * @param statusFragments 状态栏片段，可为 {@code null} 或空
     * @param panels          面板贡献，可为 {@code null} 或空
     * @return 快照，保证非 {@code null}；两者都为空时返回 {@link #empty()}
     */
    public static UiSnapshot of(List<String> statusFragments, List<OwnedPanel> panels) {
        boolean noFragments = statusFragments == null || statusFragments.isEmpty();
        boolean noPanels = panels == null || panels.isEmpty();
        if (noFragments && noPanels) {
            return EMPTY;
        }
        return new UiSnapshot(
                noFragments
                        ? Collections.<String>emptyList()
                        : Collections.unmodifiableList(new ArrayList<String>(statusFragments)),
                noPanels
                        ? Collections.<OwnedPanel>emptyList()
                        : Collections.unmodifiableList(new ArrayList<OwnedPanel>(panels)));
    }

    /**
     * 获取状态栏片段。
     *
     * @return 不可变片段列表，保证非 {@code null}
     */
    public List<String> getStatusFragments() {
        return statusFragments;
    }

    /**
     * 获取面板贡献。
     *
     * @return 不可变面板列表（按 {@code order} 升序），保证非 {@code null}
     */
    public List<OwnedPanel> getPanels() {
        return panels;
    }

    /**
     * 判断是否没有任何贡献。
     *
     * @return 片段与面板都为空返回 {@code true}
     */
    public boolean isEmpty() {
        return statusFragments.isEmpty() && panels.isEmpty();
    }

    @Override
    public String toString() {
        return "UiSnapshot{statusFragments=" + statusFragments.size()
                + ", panels=" + panels.size() + '}';
    }
}
