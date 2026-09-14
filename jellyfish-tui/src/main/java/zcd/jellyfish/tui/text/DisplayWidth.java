package zcd.jellyfish.tui.text;

/**
 * 终端显示宽度计算：一个码点占几列。
 * <p>
 * <b>为什么必须自己算</b>：{@link String#length()} 数的是 UTF-16 代码单元，与终端列数毫无关系。
 * 中文、日文、韩文、全角标点占 <b>2 列</b>，换行符、零宽连接符、变体选择符占 <b>0 列</b>，
 * 其余占 1 列。用 {@code length()} 做换行与缩进对齐，中文消息会整体错位，
 * 而中文是本项目的主力场景。
 * <p>
 * <b>为什么放在外壳而不是内核</b>：这是纯粹的渲染关注点——只有「要往终端画」的一方才知道列数这个概念。
 * 内核（session / react / command）处理的是语义上的消息，不应引入任何显示宽度假设。
 * <p>
 * 判定依据是 Unicode 的 East Asian Width 属性（W 与 F 两类计 2 列），此处按区间近似实现：
 * 覆盖 CJK、Hangul、全角形式、常用 Emoji 等实际会用到的范围，不追求全量 Unicode 表
 * （那需要几十 KB 的区间表，而本项目的实际输入是自然语言对话）。
 *
 * @author zcd
 */
public final class DisplayWidth {

    /** 组合用附加符号起点。 */
    private static final int COMBINING_START = 0x0300;

    /** 组合用附加符号终点。 */
    private static final int COMBINING_END = 0x036F;

    /** 补充组合符号起点。 */
    private static final int COMBINING_SUPPLEMENT_START = 0x1AB0;

    /** 补充组合符号终点。 */
    private static final int COMBINING_SUPPLEMENT_END = 0x1DFF;

    /** 零宽字符区间起点（零宽空格、方向标记等）。 */
    private static final int ZERO_WIDTH_START = 0x200B;

    /** 零宽字符区间终点（含零宽连接符与方向控制符）。 */
    private static final int ZERO_WIDTH_END = 0x200F;

    /** 变体选择符起点。 */
    private static final int VARIATION_SELECTOR_START = 0xFE00;

    /** 变体选择符终点。 */
    private static final int VARIATION_SELECTOR_END = 0xFE0F;

    private DisplayWidth() {
    }

    /**
     * 计算一段文本占用的终端列数。
     *
     * @param text 文本，可为 {@code null}
     * @return 列数，{@code null} 或空串返回 0
     */
    public static int of(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int codePoint = text.codePointAt(i);
            total += ofCodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
        return total;
    }

    /**
     * 计算单个码点占用的终端列数。
     *
     * @param codePoint Unicode 码点
     * @return 列数：0（零宽）、1（窄）或 2（宽）
     */
    public static int ofCodePoint(int codePoint) {
        if (isZeroWidth(codePoint)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /**
     * 判断码点是否不占列。
     * <p>
     * 组合符号与变体选择符是「贴在上一个字符上」的，只有把它们算成 0 列，
     * 含重音符号的拉丁文与带 Emoji 变体选择符的文本才不会多出一格。
     *
     * @param codePoint Unicode 码点
     * @return 不占列返回 {@code true}
     */
    public static boolean isZeroWidth(int codePoint) {
        if (codePoint >= COMBINING_START && codePoint <= COMBINING_END) {
            return true;
        }
        if (codePoint >= COMBINING_SUPPLEMENT_START && codePoint <= COMBINING_SUPPLEMENT_END) {
            return true;
        }
        if (codePoint >= ZERO_WIDTH_START && codePoint <= ZERO_WIDTH_END) {
            return true;
        }
        if (codePoint >= VARIATION_SELECTOR_START && codePoint <= VARIATION_SELECTOR_END) {
            return true;
        }
        return codePoint == 0xFEFF;
    }

    /**
     * 判断码点是否占 2 列（East Asian Wide / Fullwidth）。
     *
     * @param codePoint Unicode 码点
     * @return 占 2 列返回 {@code true}
     */
    public static boolean isWide(int codePoint) {
        return inRange(codePoint, 0x1100, 0x115F)      // Hangul Jamo 初声
                || inRange(codePoint, 0x2E80, 0x303E)  // CJK 部首、康熙部首、CJK 符号
                || inRange(codePoint, 0x3041, 0x33FF)  // 平假名 / 片假名 / 注音 / CJK 兼容
                || inRange(codePoint, 0x3400, 0x4DBF)  // CJK 扩展 A
                || inRange(codePoint, 0x4E00, 0x9FFF)  // CJK 统一表意文字
                || inRange(codePoint, 0xA000, 0xA4CF)  // 彝文
                || inRange(codePoint, 0xA960, 0xA97F)  // Hangul Jamo 扩展 A
                || inRange(codePoint, 0xAC00, 0xD7A3)  // Hangul 音节
                || inRange(codePoint, 0xF900, 0xFAFF)  // CJK 兼容表意文字
                || inRange(codePoint, 0xFE10, 0xFE19)  // 竖排标点
                || inRange(codePoint, 0xFE30, 0xFE6F)  // CJK 兼容形式
                || inRange(codePoint, 0xFF00, 0xFF60)  // 全角 ASCII
                || inRange(codePoint, 0xFFE0, 0xFFE6)  // 全角符号（含 ￥ 等）
                || inRange(codePoint, 0x1F300, 0x1F64F) // Emoji 常用区
                || inRange(codePoint, 0x1F900, 0x1F9FF) // Emoji 补充区
                || inRange(codePoint, 0x20000, 0x2FFFD) // CJK 扩展 B 及以后
                || inRange(codePoint, 0x30000, 0x3FFFD);
    }

    /**
     * 判断码点是否落在闭区间内。
     *
     * @param codePoint 码点
     * @param start     区间起点
     * @param end       区间终点
     * @return 落在区间内返回 {@code true}
     */
    private static boolean inRange(int codePoint, int start, int end) {
        return codePoint >= start && codePoint <= end;
    }
}
