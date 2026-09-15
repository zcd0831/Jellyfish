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
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class UiSegment {

    /** 文本内容。 */
    private final String text;

    /** 语义强调档位，保证非 {@code null}。 */
    private final UiEmphasis emphasis;

    /**
     * 构造文本段。
     *
     * @param text     文本内容，不可为 {@code null}
     * @param emphasis 语义强调档位，可为 {@code null}（按 {@link UiEmphasis#NORMAL} 处理）
     */
    public UiSegment(String text, UiEmphasis emphasis) {
        this.text = Objects.requireNonNull(text, "text must not be null");
        this.emphasis = emphasis == null ? UiEmphasis.NORMAL : emphasis;
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
     * 判断本段是否没有任何字符。
     *
     * @return 空文本返回 {@code true}
     */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public String toString() {
        return "UiSegment{" + text + ", " + emphasis + '}';
    }
}
