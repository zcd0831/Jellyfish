package zcd.jellyfish.plugin.skills;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code SKILL.md} 头部元信息的解析：只认文件开头的 {@code ---} 围栏与其中的扁平键值对。
 * <p>
 * <b>为什么手写而不是引入 YAML 库</b>：主流 {@code SKILL.md} 的头部实际上只用得到
 * 「{@code key: value}」与「值太长时折行」两种写法，而引入一个 YAML 解析器意味着多一份要 shade 进
 * 插件包的东西、多一份要跟内核无关的依赖。这里实现的是那个子集，并且<b>明确不是 YAML</b>——
 * 它不认识锚点、引用、嵌套映射与列表，遇到就当普通文本。
 * <p>
 * <b>正文与元信息必须分开</b>：元信息（{@code name} / {@code description}）每轮都要进 system prompt，
 * 而正文只在模型真正加载这个 skill 时才出现。把整份文件当成正文，等于让「渐进披露」失效。
 * <p>
 * <b>围栏缺失不算错误</b>：没有头部的 {@code SKILL.md} 是合法的——此时没有 name（用目录名兜底）
 * 也没有 description（调用方按「无法被模型选中」处理）。这一层只做解析，判定留给扫描器。
 * <p>
 * <b>折行值的两种写法</b>：{@code description:} 后面直接换行再缩进，或写成 YAML 的块标量标记
 * （{@code >} / {@code |} 及其 {@code -} / {@code +} 变体）。两者都按「后续缩进行拼成一段」处理，
 * 不做保序换行区分——这一段的用途是给模型看的一句话描述，折行方式没有信息量。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SkillFrontmatter {

    /** 头部围栏标记。 */
    private static final String FENCE = "---";

    /** 头部键值对，键已归一为小写。 */
    private final Map<String, String> values;

    /** 围栏之后的正文；没有头部时为整份内容。 */
    private final String body;

    /**
     * 构造解析结果。
     *
     * @param values 头部键值对，键为小写
     * @param body   正文
     */
    private SkillFrontmatter(Map<String, String> values, String body) {
        this.values = values;
        this.body = body;
    }

    /**
     * 解析一份 {@code SKILL.md} 的内容。
     *
     * @param content 文件原文，可为 {@code null}（按空文件处理）
     * @return 解析结果，保证非 {@code null}
     */
    static SkillFrontmatter parse(String content) {
        if (content == null || content.isEmpty()) {
            return new SkillFrontmatter(Collections.<String, String>emptyMap(), "");
        }
        // 去掉 BOM：Windows 上编辑过的文件会带上它，而它藏在第一个字符里，
        // 会让围栏判断莫名其妙地失败
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        String[] lines = text.split("\n", -1);
        if (!FENCE.equals(lines[0].trim())) {
            return new SkillFrontmatter(Collections.<String, String>emptyMap(), text);
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        int index = 1;
        int bodyStart = -1;
        while (index < lines.length) {
            String line = lines[index];
            if (FENCE.equals(line.trim())) {
                bodyStart = index + 1;
                break;
            }
            if (line.trim().isEmpty() || isIndented(line)) {
                // 空行与缩进行都属于上一条键的值，已在下面被消费掉；这里只为容错
                index++;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                index++;
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase();
            String inline = line.substring(colon + 1).trim();
            index++;
            if (inline.isEmpty() || isBlockMarker(inline)) {
                StringBuilder folded = new StringBuilder();
                while (index < lines.length && !FENCE.equals(lines[index].trim())
                        && (lines[index].trim().isEmpty() || isIndented(lines[index]))) {
                    if (folded.length() > 0 && !lines[index].trim().isEmpty()) {
                        folded.append(' ');
                    }
                    folded.append(lines[index].trim());
                    index++;
                }
                values.put(key, unquote(folded.toString().trim()));
            } else {
                values.put(key, unquote(inline));
            }
        }
        if (bodyStart < 0) {
            // 围栏没闭合：宁可当成「没有头部」也不要把它当成「头部吃掉了整份文件」——
            // 后者会把一份内容完好的说明变成空正文，而模型无从知道发生了什么
            return new SkillFrontmatter(Collections.<String, String>emptyMap(), text);
        }
        return new SkillFrontmatter(Collections.unmodifiableMap(values), join(lines, bodyStart));
    }

    /**
     * 获取头部键值。
     *
     * @param key 键名，大小写不敏感
     * @return 值；不存在时返回 {@code null}
     */
    String value(String key) {
        return key == null ? null : values.get(key.toLowerCase());
    }

    /**
     * 获取 {@code name}。
     *
     * @return 名称；未声明或为空白时返回 {@code null}
     */
    String name() {
        return blankToNull(value("name"));
    }

    /**
     * 获取 {@code description}。
     *
     * @return 描述；未声明或为空白时返回 {@code null}
     */
    String description() {
        return blankToNull(value("description"));
    }

    /**
     * 获取正文。
     *
     * @return 围栏之后的正文；没有头部时为整份内容。保证非 {@code null}
     */
    String body() {
        return body;
    }

    /**
     * 判断一行是否以空白开头（折行值的缩进判据）。
     *
     * @param line 一行文本
     * @return 以空白开头返回 {@code true}
     */
    private static boolean isIndented(String line) {
        return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    /**
     * 判断一个内联值是否是 YAML 的块标量标记。
     * <p>
     * 只认 {@code >} / {@code |} 加可选 {@code -} / {@code +}。
     * 不认的话，{@code description: &gt;} 会被当成描述就是那个尖括号，而真正的描述被丢掉。
     *
     * @param inline 内联值
     * @return 是块标量标记返回 {@code true}
     */
    private static boolean isBlockMarker(String inline) {
        if (inline.isEmpty() || (inline.charAt(0) != '>' && inline.charAt(0) != '|')) {
            return false;
        }
        String rest = inline.substring(1);
        return rest.isEmpty() || "-".equals(rest) || "+".equals(rest);
    }

    /**
     * 去掉值两端成对的引号。
     *
     * @param value 原始值
     * @return 去引号后的值
     */
    private static String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' || first == '\'') && first == last) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 把空白串归一为 {@code null}。
     *
     * @param value 原始值
     * @return 非空白返回原值，否则返回 {@code null}
     */
    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /**
     * 从指定行开始拼回正文。
     *
     * @param lines     全部行
     * @param bodyStart 正文起始行下标（0 基）；等于 {@code lines.length} 时正文为空
     * @return 正文文本
     */
    private static String join(String[] lines, int bodyStart) {
        if (bodyStart >= lines.length) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = bodyStart; index < lines.length; index++) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(lines[index]);
        }
        return text.toString();
    }
}
