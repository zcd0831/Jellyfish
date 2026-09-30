package zcd.jellyfish.api.ui;

import java.util.Objects;

/**
 * 一行界面文本里的一段：文本 + 语义强调档位。
 * <p>
 * <b>为什么不直接给一个字符串</b>：面板里通常只有少数字需要强调（例如「已索引 12/40 个文件」里的
 * {@code 12/40}），若只给整段纯文本，插件要么放弃强调，要么用 ANSI 转义序列污染数据——
 * 后者会让外壳无法按显示宽度正确折行（转义序列不占列但在 {@code String} 里占字符）。
 * <p>
 * 段与段的组合只表达「同一行内样式切换」，<b>不表达嵌套或对齐</b>：行内的对齐是外壳的事。
 * <p>
 * <b>两个维度：种类与强调</b>。{@link UiSegmentKind} 说「这一段是什么」（决定修饰：粗体 / 反显 / 下划线 /
 * 斜体），{@link UiEmphasis} 说「这一段该多抢眼」（决定颜色）。两者正交，互不覆盖；
 * 因此面板能有一点头部、一点点代码感，而插件不必猜颜色。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiSegment {

    /** 文本内容。 */
    private final String text;

    /** 语义强调档位，保证非 {@code null}。 */
    private final UiEmphasis emphasis;

    /** 语义种类，保证非 {@code null}。 */
    private final UiSegmentKind kind;

    /**
     * 构造文本段。
     * <p>
     * <b>它仍然是本类唯一的可见构造器</b>：新增字段走一个私有重载与静态工厂，而不是再加一个公开构造器。
     * 这条纪律的由来见 {@code constraints/extensions.md}：快照类一旦多出可见构造器，
     * Jackson 的隐式创建器就不再唯一。
     *
     * @param text     文本内容，不可为 {@code null}
     * @param emphasis 语义强调档位，可为 {@code null}（按 {@link UiEmphasis#NORMAL} 处理）
     */
    public UiSegment(String text, UiEmphasis emphasis) {
        this(text, emphasis, UiSegmentKind.TEXT);
    }

    /**
     * 构造带种类的文本段。
     *
     * @param text     文本内容，不可为 {@code null}
     * @param emphasis 语义强调档位，可为 {@code null}
     * @param kind     语义种类，可为 {@code null}（按 {@link UiSegmentKind#TEXT} 处理）
     */
    private UiSegment(String text, UiEmphasis emphasis, UiSegmentKind kind) {
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.emphasis = emphasis == null ? UiEmphasis.NORMAL : emphasis;
        this.kind = kind == null ? UiSegmentKind.TEXT : kind;
    }

    /**
     * 构造常规文本段。
     *
     * @param text 文本内容，不可为 {@code null}
     * @return 文本段
     */
    public static UiSegment of(String text) {
        return new UiSegment(text, UiEmphasis.NORMAL);
    }

    /**
     * 构造文本段。
     *
     * @param text     文本内容，不可为 {@code null}
     * @param emphasis 语义强调档位，可为 {@code null}（按 {@link UiEmphasis#NORMAL} 处理）
     * @return 文本段
     */
    public static UiSegment of(String text, UiEmphasis emphasis) {
        return new UiSegment(text, emphasis);
    }

    /**
     * 构造带种类的文本段。
     *
     * @param text     文本内容，不可为 {@code null}
     * @param emphasis 语义强调档位，可为 {@code null}（按 {@link UiEmphasis#NORMAL} 处理）
     * @param kind     语义种类，可为 {@code null}（按 {@link UiSegmentKind#TEXT} 处理）
     * @return 文本段
     */
    public static UiSegment of(String text, UiEmphasis emphasis, UiSegmentKind kind) {
        return new UiSegment(text, emphasis, kind);
    }

    /**
     * 获取文本内容。
     *
     * @return 文本内容，保证非 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取语义强调档位。
     *
     * @return 强调档位，保证非 {@code null}
     */
    public UiEmphasis getEmphasis() {
        return emphasis;
    }

    /**
     * 获取语义种类。
     *
     * @return 种类，保证非 {@code null}
     */
    public UiSegmentKind getKind() {
        return kind;
    }

    /**
     * 判断本段是否没有任何字符。
     *
     * @return 空文本返回 {@code true}
     */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return "UiSegment{" + text + ", " + emphasis + ", " + kind + '}';
    }
}
