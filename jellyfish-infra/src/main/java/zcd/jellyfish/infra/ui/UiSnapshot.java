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
 * 可用宽度的那一刻。owner 属于「谁贡献的」这类归因信息，收集期间只用来去重，不进快照。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiSnapshot {

    /** 空快照：没有任何插件贡献。 */
    private static final UiSnapshot EMPTY = new UiSnapshot(Collections.<String>emptyList());

    /** 状态栏片段，按注册顺序排列。 */
    private final List<String> statusFragments;

    /**
     * 构造快照。
     *
     * @param statusFragments 状态栏片段，不可为 {@code null}
     */
    private UiSnapshot(List<String> statusFragments) {
        this.statusFragments = statusFragments;
    }

    /**
     * 构造空快照。
     *
     * @return 没有任何片段的快照
     */
    public static UiSnapshot empty() {
        return EMPTY;
    }

    /**
     * 构造快照。
     *
     * @param statusFragments 状态栏片段，可为 {@code null} 或空（等价于 {@link #empty()}）
     * @return 快照，保证非 {@code null}
     */
    public static UiSnapshot of(List<String> statusFragments) {
        if (statusFragments == null || statusFragments.isEmpty()) {
            return EMPTY;
        }
        return new UiSnapshot(Collections.unmodifiableList(new ArrayList<String>(statusFragments)));
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
     * 判断是否没有任何贡献。
     *
     * @return 没有任何片段返回 {@code true}
     */
    public boolean isEmpty() {
        return statusFragments.isEmpty();
    }

    @Override
    public String toString() {
        return "UiSnapshot{statusFragments=" + statusFragments.size() + '}';
    }
}
