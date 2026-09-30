package zcd.jellyfish.api.extension;

/**
 * 输入改写结果：插件在 {@link InputTransformRequest} 上能表达的结果。
 * <p>
 * 三态：不改、换一段文本、把这次输入整个接过去。
 * <p>
 * <b>{@link #handled(String)} 是与前两者的本质区别</b>：它表示「这次输入不进对话了」——
 * 外壳只把那条说明贴出来（TUI 的提示行 / {@code -cli} 的 stdout / Server 的 SSE），
 * 不解析指令、不建会话、不起回合。典型用法是快捷指令（{@code ?help} → 自己回一段说明）。
 * <p>
 * <b>为什么把说明放在结果里而不是让插件自己写屏幕</b>：插件拿不到外壳，这也正是
 * 「插件只声明、内核才执行」那条边界的形状。给一段文本、由外壳按自己的口径渲染，
 * 与命令结果的处置完全一致。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputTransformResult {

    /** 单例：不改。 */
    private static final InputTransformResult CONTINUE = new InputTransformResult(false, false, null, null);

    /** 是否被接过去（不进对话）。 */
    private final boolean handled;

    /** 是否要替换文本。 */
    private final boolean replaced;

    /** 替换后的文本，仅替换态有意义。 */
    private final String text;

    /** 接过去时贴给用户的说明，仅 {@link #handled} 态有意义。 */
    private final String notice;

    /**
     * 构造结果。
     *
     * @param handled  是否被接过去
     * @param replaced 是否要替换文本
     * @param text     替换后的文本，可为 {@code null}
     * @param notice   接过去时贴给用户的说明，可为 {@code null}
     */
    public InputTransformResult(boolean handled, boolean replaced, String text, String notice) {
        this.handled = handled;
        this.replaced = replaced;
        this.text = text;
        this.notice = notice;
    }

    /**
     * 构造「不改」结果。
     *
     * @return 结果
     */
    public static InputTransformResult continueAsIs() {
        return CONTINUE;
    }

    /**
     * 构造「替换文本」结果。
     * <p>
     * 传 {@code null} 等价于「不改」：用 {@code null} 同时表示「不改」与「改成空」
     * 会让调用点的判断无法解释。
     *
     * @param text 替换后的文本，可为 {@code null}
     * @return 结果
     */
    public static InputTransformResult replace(String text) {
        return text == null ? CONTINUE : new InputTransformResult(false, true, text, null);
    }

    /**
     * 构造「接过去、不进对话」结果。
     *
     * @param notice 贴给用户的说明，可为 {@code null}（外壳写固定占位）
     * @return 结果
     */
    public static InputTransformResult handled(String notice) {
        return new InputTransformResult(true, false, null, notice);
    }

    /**
     * 判断是否被接过去。
     *
     * @return 接过去返回 {@code true}
     */
    public boolean isHandled() {
        return handled;
    }

    /**
     * 判断是否要替换文本。
     *
     * @return 要替换返回 {@code true}
     */
    public boolean hasText() {
        return replaced && text != null;
    }

    /**
     * 获取替换后的文本。
     *
     * @return 替换后的文本，非替换态时为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取接过去时贴给用户的说明。
     *
     * @return 说明，未被接过去时为 {@code null}
     */
    public String getNotice() {
        return notice;
    }

    @Override
    public String toString() {
        return "InputTransformResult{handled=" + handled + ", replaced=" + replaced + '}';
    }
}
