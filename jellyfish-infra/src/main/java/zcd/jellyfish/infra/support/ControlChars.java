package zcd.jellyfish.infra.support;

/**
 * 控制字符过滤：把来自模型 / 工具参数 / 插件贡献的文本里会干扰终端的内容剔掉。
 * <p>
 * <b>为什么必须有这一层</b>：这些文本是<b>不可信输入</b>——工具参数由模型生成，而终端把
 * {@code ESC}（{@code \u001b}）当控制序列的引导符。一段参数里带上 {@code ESC[2J} 就能清屏、
 * {@code ESC[H} 就能把光标挪回左上角覆盖界面、{@code \r} 能把已显示的一行原地改写。
 * 这不是「显示得不好看」的问题，而是「屏幕显示的内容与工具真正要执行的内容不是同一回事」
 * ——审批框恰恰建立在这两者一致的前提上。
 * <p>
 * <b>为什么放在 infra 而不是某个外壳里</b>：要过滤的边界是「不可信文本进入终端 / 日志」这个事实，
 * 它与外壳无关——{@code -cli} 写 stderr、{@code -tui} 画屏幕、日志落文件，三处都可能被一个
 * {@code ESC} 改写。口径只有一份，才能保证三条输出路径的过滤规则不会各自漂移。
 * <b>过滤规则</b>：丢弃全部 {@code Character.CONTROL} 类型的码点（C0 与 C1，含 DEL），
 * 只保留 {@code \n}（换行是排版语义，且已经由外层自己掌控）；
 * 额外丢弃双向控制符（{@code U+202A}～{@code U+202E}、{@code U+2066}～{@code U+2069}），
 * 它们能让一段文本在视觉上读作与真实内容相反的顺序。制表符换成空格而不是删除，
 * 否则 {@code a\tb} 会粘成 {@code ab} 而改变参数含义。
 *
 * @author zcd
 */
public final class ControlChars {

    /**
     * 常量类，禁止实例化。
     */
    private ControlChars() {
    }

    /**
     * 过滤文本里的控制字符。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 过滤后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                sb.append('\n');
            } else if (codePoint == '\t') {
                sb.append(' ');
            } else if (isBidiControl(codePoint)) {
                continue;
            } else if (Character.getType(codePoint) == Character.CONTROL) {
                continue;
            } else {
                sb.appendCodePoint(codePoint);
            }
        }
        return sb.toString();
    }

    /**
     * 判断是否为双向控制符。
     *
     * @param codePoint 码点
     * @return 是双向控制符返回 {@code true}
     */
    private static boolean isBidiControl(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }
}
