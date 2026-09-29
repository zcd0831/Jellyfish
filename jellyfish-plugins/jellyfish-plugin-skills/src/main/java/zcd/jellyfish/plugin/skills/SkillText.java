package zcd.jellyfish.plugin.skills;

import java.util.Locale;

/**
 * 本插件内部的文本小工具：体积格式化与单行折叠截断。
 * <p>
 * <b>为什么单独收一处</b>：这两件事同时出现在工具输出、提示词清单与 {@code /skills} 台账三处，
 * 各自写一遍的结果是「同一个 skill 在屏幕上显示成三种体积」。它们小到不值得各写一份，
 * 也不涉及任何策略，因此集中在这里。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class SkillText {

    /**
     * 工具类，禁止实例化。
     */
    private SkillText() {
    }

    /**
     * 把字节数转成一眼能读懂的体积文本。
     *
     * @param bytes 字节数
     * @return 体积文本
     */
    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 把文本折叠成单行并按字符数截断。
     * <p>
     * <b>必须先折叠空白</b>：这些文本会被拼进 system prompt 的清单与命令行输出，
     * 一段带换行的描述能把两者的排版一起打乱。
     *
     * @param text     原始文本，可为 {@code null}
     * @param maxChars 字符上限
     * @return 折叠并截断后的单行文本；输入为 {@code null} 时返回空串
     */
    static String singleLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        return collapsed.substring(0, maxChars) + "…";
    }
}
