package zcd.jellyfish.infra.tooloutput;

/**
 * 工具输出的头尾预览：把一段文本压进字符预算，保留开头与结尾，中间换成一条省略标记。
 * <p>
 * <b>为什么是头尾而不是只留头</b>：工具输出的结论往往在末尾——编译错误、测试失败、进程退出信息。
 * 只留头会让模型看到「一切正常的前 90%」而错过真正的那一行。行业实现（Codex、Gemini CLI、
 * Roo Code、OpenHands）也一律是头尾。
 * <p>
 * <b>为什么尾重（30/70）</b>：面向终端的实现普遍偏尾（Gemini CLI 与 Roo Code 都是 20/80）。
 * 均分适合要同时服务 diff、JSON 等多种载荷的通用场景，而本内核要压的正文里最长的那一类就是命令输出。
 * <p>
 * <b>省略标记是必需的，不是装饰</b>：头尾在语义上不再连续，模型很容易把两段接起来读成一份完整内容。
 * 标记必须写明省略了多少行、多少字符，让「我看到的是预览」这件事对模型可见。
 * <p>
 * <b>两处调用方共用本类</b>：事后截断（{@link ToolOutputLimiter}）与捕获期溢出
 * （{@link SpillCapturingSink}）。这是刻意的——两处各写一份切分逻辑，迟早会漂移成
 * 「两条路径的阈值与标记不一样」。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class ToolOutputPreview {

    /** 头占的百分比，尾取剩下的部分。 */
    private static final int HEAD_PERCENT = 30;

    /** 省略标记的预算预留：装两个十进制计数与固定文案。 */
    private static final int MARKER_RESERVE = 48;

    /**
     * 低于这个预算就不再尝试头尾。
     * <p>
     * 退化时只留头且不加标记：此时两段之间没有省略，本来就不会被误读成连续内容；
     * 而且信封本身已经带着 {@code _truncated} 与总量，标注的职责不落在预览文本上。
     */
    private static final int MIN_HEAD_TAIL_BUDGET = 64;

    /**
     * 工具类，禁止实例化。
     */
    private ToolOutputPreview() {
    }

    /**
     * 计算正文预算里头占用的字符数。
     *
     * @param bodyBudget 已扣掉省略标记的正文预算，保证非负
     * @return 头预算，保证非负
     */
    static int headBudget(int bodyBudget) {
        return bodyBudget * HEAD_PERCENT / 100;
    }

    /**
     * 计算正文预算里尾占用的字符数。
     *
     * @param bodyBudget 已扣掉省略标记的正文预算，保证非负
     * @return 尾预算，保证非负
     */
    static int tailBudget(int bodyBudget) {
        return bodyBudget - headBudget(bodyBudget);
    }

    /**
     * 扣掉省略标记预留后的正文预算。
     *
     * @param budget 总预算
     * @return 正文预算，保证非负
     */
    static int bodyBudget(int budget) {
        return Math.max(0, budget - MARKER_RESERVE);
    }

    /**
     * 取文本开头的一段，优先在换行处收尾。
     * <p>
     * 「优先在换行处收尾」是为了不把一行切成两半：半行看起来像是原文就断在那里。断点离目标位置
     * 太远（不足一半）时宁可按字符切——否则一个超长首行会把预览压成一小截。
     *
     * @param text   原文，可为 {@code null}
     * @param budget 字符预算
     * @return 开头片段，保证非 {@code null}
     */
    static String head(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        int end = safeCut(text, budget);
        int newline = text.lastIndexOf('\n', end - 1);
        int cut = newline >= end / 2 ? newline + 1 : end;
        return text.substring(0, safeCut(text, cut));
    }

    /**
     * 取文本结尾的一段，优先从换行之后开始。
     *
     * @param text   原文，可为 {@code null}
     * @param budget 字符预算
     * @return 结尾片段，保证非 {@code null}
     */
    static String tail(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        int start = Math.max(0, text.length() - budget);
        int newline = text.indexOf('\n', start);
        if (newline >= 0 && newline + 1 - start <= budget / 2) {
            start = newline + 1;
        }
        // 不能从低位代理开始：那会把一个码点劈成两半
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }
        return text.substring(Math.min(start, text.length()));
    }

    /**
     * 把已经分好的头与尾拼成预览，中间插入省略标记。
     * <p>
     * 供捕获期溢出使用：那时完整文本已经不在内存里，只有头段、尾段与总量。
     *
     * @param head         头段，可为 {@code null}
     * @param tail         尾段，可为 {@code null}
     * @param omittedLines 省略的行数，保证非负
     * @param omittedChars 省略的字符数，保证非负
     * @param budget       总预算
     * @return 预览文本，保证非 {@code null}
     */
    static String join(String head, String tail, long omittedLines, long omittedChars, int budget) {
        String prefix = head == null ? "" : head;
        String suffix = tail == null ? "" : tail;
        String marker = marker(omittedLines, omittedChars);
        int excess = prefix.length() + marker.length() + suffix.length() - budget;
        if (excess > 0) {
            // 只可能因计数位数超出预留：按差额继续从尾部前侧收缩
            suffix = trimTailToBudget(suffix, Math.max(0, suffix.length() - excess));
        }
        return prefix + marker + suffix;
    }

    /**
     * 把一段完整文本压进预算：短于预算原样返回，否则取头尾并加省略标记。
     *
     * @param text   原文，可为 {@code null}
     * @param budget 字符预算
     * @return 预览文本，保证非 {@code null}
     */
    static String text(String text, int budget) {
        if (text == null || text.isEmpty() || budget <= 0) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        if (budget <= MIN_HEAD_TAIL_BUDGET) {
            return head(text, budget);
        }
        int body = bodyBudget(budget);
        String prefix = head(text, headBudget(body));
        String suffix = tail(text, tailBudget(body));
        int cutFrom = prefix.length();
        int cutTo = text.length() - suffix.length();
        long omittedChars = (long) text.length() - prefix.length() - suffix.length();
        return join(prefix, suffix, countNewlines(text, cutFrom, cutTo), omittedChars, budget);
    }

    /**
     * 生成省略标记。
     *
     * @param omittedLines 省略的行数
     * @param omittedChars 省略的字符数
     * @return 标记文本，保证非 {@code null}
     */
    private static String marker(long omittedLines, long omittedChars) {
        return "\n… 省略 " + omittedLines + " 行 / " + omittedChars + " 字符 …\n";
    }

    /**
     * 把尾部片段收缩到指定长度，保留它的末尾。
     *
     * @param tail   尾部片段
     * @param budget 目标长度
     * @return 收缩后的片段
     */
    private static String trimTailToBudget(String tail, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (tail.length() <= budget) {
            return tail;
        }
        return tail(tail, budget);
    }

    /**
     * 取不会切开代理对的截断位置。
     *
     * @param text  文本
     * @param index 期望截断位置
     * @return 修正后的位置
     */
    private static int safeCut(String text, int index) {
        int cut = Math.max(0, Math.min(index, text.length()));
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    /**
     * 统计区间 {@code [from, to)} 内的换行数。
     *
     * @param text 文本
     * @param from 起始下标
     * @param to   结束下标
     * @return 换行个数，保证非负
     */
    private static long countNewlines(String text, int from, int to) {
        int start = Math.max(0, Math.min(from, text.length()));
        int end = Math.max(start, Math.min(to, text.length()));
        long lines = 0L;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
