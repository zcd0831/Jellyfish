package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 行内引用候选：补全面板里的一行，以及「选中后往输入框里插入什么」。
 * <p>
 * <b>{@code label} 与 {@code insertText} 分开</b>：{@code label} 是给人看的（如 {@code main/}），
 * {@code insertText} 是回填进输入框的片段（可能是带转义的路径）。两者通常相同，
 * 但路径含空格、含引号时就不是——把它们合并成一个字段会逼着渲染层去反解转义。
 * <p>
 * <b>插件不返回绝对偏移</b>：要替换哪一段由内核算（见 {@code InputReferenceRequest} 的注释），
 * 插件只回答「替换成什么」，因此插件的实现与光标位置完全解耦。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputReferenceChoice {

    /** 显示文本。 */
    private final String label;

    /** 选中后插入输入框的片段，可为 {@code null} 或空白（回退为 {@link #label}）。 */
    private final String insertText;

    /** 补充说明（如文件大小、类型），未提供时为 {@code null}。 */
    private final String detail;

    /**
     * 构造引用候选。
     *
     * @param label      显示文本，不可为空白
     * @param insertText 选中后插入的片段，可为 {@code null} 或空白（回退为 {@code label}）
     * @param detail     补充说明，可为 {@code null}
     * @throws JellyfishException {@code label} 为空白时抛出
     */
    public InputReferenceChoice(String label, String insertText, String detail) {
        if (label == null || label.trim().isEmpty()) {
            throw new JellyfishException("reference choice label must not be blank");
        }
        this.label = label;
        this.insertText = insertText == null || insertText.isEmpty() ? label : insertText;
        this.detail = detail;
    }

    /**
     * 获取显示文本。
     *
     * @return 显示文本，保证非 {@code null}
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取选中后插入输入框的片段。
     *
     * @return 插入片段，保证非 {@code null}
     */
    public String getInsertText() {
        return insertText;
    }

    /**
     * 获取补充说明。
     *
     * @return 补充说明，未提供时为 {@code null}
     */
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "InputReferenceChoice{label=" + label + '}';
    }
}
