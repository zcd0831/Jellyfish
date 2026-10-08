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
        // 快速路径：屏幕文本里绝大多数本来就是干净的。它的调用点在渲染帧里（`StyledSegment` 的
        // 构造器），每帧每段都要过一遍，因此先扫一遍、干净就原样返回，省掉一次分配与一次复制
        if (!needsStripping(text)) {
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
     * 把文本压成单行：过滤控制字符，并把换行换成空格。
     * <p>
     * <b>为什么换行要单独处理</b>：{@link #strip(String)} 保留换行是对的（它是排版语义，流式增量输出靠它断行），
     * 但在「这段文本必须在单行里出现」的语境下换行就是一个可用的注入手段——一段带 {@code \n} 的输入
     * 能在日志里伪造出一整行（{@code 会话不存在} 之后跟一行 {@code 已授权}），在终端上伪装成外壳自己打的诊断。
     * 因此这里比 {@code strip} 更严一点，而严的那部分与「谁在用」无关：日志行与 CLI 诊断行都要求单行。
     * <p>
     * <b>不含长度上限</b>：单行不等于短行——CLI 的整行诊断（工具完成行）可以很长，日志的限长是日志自己的事
     * （见 serve 侧的 {@code LogText}），截断逻辑与「单行」这条规则不该混在一起。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 单行文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String singleLine(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // strip 自己有「干净即原样返回」的快速路径，String.replace 在无匹配时也返回原串，
        // 因此这里不必再判一次：干净且无换行的文本零分配
        return strip(text).replace('\n', ' ');
    }

    /**
     * 判断文本里是否含有需要处理的码点。
     * <p>
     * 判据必须与 {@link #strip(String)} 的过滤规则逐一对应：多认一种（例如把普通空白也算上）
     * 会让快速路径失效、退回逐字符复制；少认一种就会漏过它、让过滤形同不存在。
     *
     * @param text 非空文本
     * @return 需要处理返回 {@code true}
     */
    private static boolean needsStripping(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                continue;
            }
            if (codePoint == '\t' || isBidiControl(codePoint)
                    || Character.getType(codePoint) == Character.CONTROL) {
                return true;
            }
        }
        return false;
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
