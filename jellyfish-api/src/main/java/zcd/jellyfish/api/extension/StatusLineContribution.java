package zcd.jellyfish.api.extension;

/**
 * 状态栏贡献结果：插件往状态栏尾部追加的一小段文本。
 * <p>
 * <b>为什么是一段纯文本而不是结构化字段</b>：要显示什么只有插件自己懂（待办进度、索引状态、
 * 正在跑的后台任务……），内核既不解析也不改写，只负责按注册顺序拼接。与 {@code PromptContribution}
 * 同构，返回空贡献是正当用法：插件会被反复询问，但只有真的有话要说时才返回文本。
 * <p>
 * <b>形态是「拼接型」</b>：状态栏是一行，多个插件的片段<b>共存</b>而不是互相抢占，因此本扩展点
 * 不需要 {@code UiRegion}（那是面板型的落位问题），也不需要用户切换命令。
 * <p>
 * <b>文本请自带归属</b>：内核<b>不</b>给片段加 {@code pluginId} 前缀——那是插件自己的事。
 * 状态栏空间很窄（通常只剩 30～60 列），建议极简并自带可辨识的写法，例如 {@code 待办 2/5}。
 * <p>
 * <b>外壳负责收尾</b>：外壳按终端宽度从最后一个片段开始整块丢弃超出的部分。
 * 状态栏没有富文本，插件只给纯文本，也无权控制宽度与位置。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class StatusLineContribution {

    /** 空贡献：这次没有要显示的片段。 */
    private static final StatusLineContribution EMPTY = new StatusLineContribution(null);

    /** 片段文本，无贡献时为 {@code null}。 */
    private final String text;

    /**
     * 构造贡献。
     *
     * @param text 片段文本，可为 {@code null}
     */
    private StatusLineContribution(String text) {
        this.text = text;
    }

    /**
     * 构造贡献；文本为空白时等价于 {@link #empty()}。
     * <p>
     * 空白归一为空贡献而不是原样保留：状态栏的每一列都很贵，一个空格片段只会在拼接处留下双倍间隔。
     *
     * @param text 片段文本，可为 {@code null}
     * @return 贡献结果，保证非 {@code null}
     */
    public static StatusLineContribution of(String text) {
        return text == null || text.trim().isEmpty() ? EMPTY : new StatusLineContribution(text.trim());
    }

    /**
     * 构造空贡献。
     *
     * @return 没有内容的贡献
     */
    public static StatusLineContribution empty() {
        return EMPTY;
    }

    /**
     * 获取片段文本。
     *
     * @return 片段文本，无贡献时为 {@code null}
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
        return "StatusLineContribution{text=" + text + '}';
    }
}
