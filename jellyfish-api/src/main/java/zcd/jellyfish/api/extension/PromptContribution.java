package zcd.jellyfish.api.extension;

/**
 * 提示词贡献结果：插件为本次请求的 system prompt 追加的一段上下文。
 * <p>
 * <b>为什么是一段文本而不是结构化字段</b>：贡献的内容只有插件自己懂（待办清单、记忆召回、
 * 项目约定……），内核既不解析也不改写，只负责按注册顺序拼接。用值类型而不是裸 {@code String}，
 * 是为了与其它扩展点的结果类型同构，并给「没有要贡献的」留出统一的表达（{@link #empty()}）。
 * <p>
 * 返回空贡献是正当用法：插件每轮都会被问到，但只有在真的有话要说时才返回文本。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PromptContribution {

    /** 空贡献：本轮没有要追加的上下文。 */
    private static final PromptContribution EMPTY = new PromptContribution(null);

    /** 贡献文本，无贡献时为 {@code null}。 */
    private final String text;

    /**
     * 构造贡献。
     *
     * @param text 贡献文本，可为 {@code null}
     */
    private PromptContribution(String text) {
        this.text = text;
    }

    /**
     * 构造贡献；文本为空白时等价于 {@link #empty()}。
     *
     * @param text 贡献文本，可为 {@code null}
     * @return 贡献结果，保证非 {@code null}
     */
    public static PromptContribution of(String text) {
        return text == null || text.trim().isEmpty() ? EMPTY : new PromptContribution(text);
    }

    /**
     * 构造空贡献。
     *
     * @return 没有内容的贡献
     */
    public static PromptContribution empty() {
        return EMPTY;
    }

    /**
     * 获取贡献文本。
     *
     * @return 贡献文本，无贡献时为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 判断是否没有贡献内容。
     *
     * @return 无贡献返回 {@code true}
     */
    public boolean isEmpty() {
        return text == null;
    }

    @Override
    public String toString() {
        // 刻意不打印正文：贡献块可能是整段上下文，混进日志行会很难看
        return "PromptContribution{length=" + (text == null ? 0 : text.length()) + '}';
    }
}
