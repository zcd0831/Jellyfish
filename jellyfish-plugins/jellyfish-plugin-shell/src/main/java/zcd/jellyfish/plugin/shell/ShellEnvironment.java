package zcd.jellyfish.plugin.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子进程环境变量的构造：继承 + 默认脱敏 + 防挂死覆盖。
 * <p>
 * <b>为什么继承而不是严格白名单</b>：脚本插件用的是严格白名单（那些进程只需要解释器与几个专用变量），
 * 而命令行工具丢掉 {@code PATH} 就意味着几乎所有命令都是 command not found。
 * <b>代价是脱敏必须默认开启</b>：工具输出会被送到远端 LLM，一次 {@code env} 或 {@code printenv}
 * 就是一次凭据外泄，而用户不会记得为这件事做配置。
 * <p>
 * <b>三条叠加顺序不能颠倒</b>：先按模式剔除、再注入防挂死默认值、最后叠加用户显式配置。
 * 用户写在 {@code environment} 里的值必须能盖掉默认值——「可配置」的含义就是「我说了算」。
 * <p>
 * 无状态，可在任意线程调用。
 *
 * @author zcd
 */
final class ShellEnvironment {

    /**
     * 防挂死与稳定输出的默认变量。
     * <p>
     * 前两条挡的是分页器（{@code git log} 会停在 {@code less} 里等按键），
     * 第三条挡的是凭据提示（{@code git push} 会在没有终端时一直等），
     * 后三条让输出不带颜色与光标控制——那些转义序列对模型没有价值，只会浪费上下文。
     */
    static final Map<String, String> ANTI_HANG = Collections.unmodifiableMap(antiHang());

    /**
     * 工具类，禁止实例化。
     */
    private ShellEnvironment() {
    }

    /**
     * 构造子进程环境变量。
     *
     * @param parent            父进程环境变量，可为 {@code null}（等价空）
     * @param sensitivePatterns 需要剔除的名字模式（内置表 + 用户追加），可为 {@code null}
     * @param overrides         用户显式配置的变量，可为 {@code null}
     * @return 子进程环境变量，保证非 {@code null}
     */
    static Map<String, String> build(Map<String, String> parent, List<String> sensitivePatterns,
                                     Map<String, String> overrides) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (parent != null) {
            for (Map.Entry<String, String> entry : parent.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (isSensitive(entry.getKey(), sensitivePatterns)) {
                    continue;
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }
        result.putAll(ANTI_HANG);
        if (overrides != null) {
            result.putAll(overrides);
        }
        return result;
    }

    /**
     * 合并内置脱敏模式与用户追加模式。
     *
     * @param extra 用户追加模式，可为 {@code null}
     * @return 合并结果，保证非 {@code null}
     */
    static List<String> sensitivePatterns(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return PluginConfig.DEFAULT_SENSITIVE_PATTERNS;
        }
        List<String> merged = new ArrayList<String>(PluginConfig.DEFAULT_SENSITIVE_PATTERNS.size() + extra.size());
        merged.addAll(PluginConfig.DEFAULT_SENSITIVE_PATTERNS);
        merged.addAll(extra);
        return Collections.unmodifiableList(merged);
    }

    /**
     * 判断变量名是否命中脱敏模式。
     *
     * @param name     变量名
     * @param patterns 模式列表，可为 {@code null}
     * @return 需要剔除返回 {@code true}
     */
    private static boolean isSensitive(String name, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 大小写不敏感的通配匹配，只支持 {@code *} 与 {@code ?}。
     * <p>
     * 刻意不引入正则：模式来自配置，用正则就等于让配置里的一处笔误变成一次 {@code PatternSyntaxException}，
     * 而这里只想要「名字里带 KEY 就算」这种朴素语义。
     *
     * @param pattern 模式，不可为 {@code null}
     * @param name    变量名
     * @return 匹配返回 {@code true}
     */
    static boolean matches(String pattern, String name) {
        return matchIgnoreCase(pattern, 0, name, 0);
    }

    /**
     * 通配匹配的递归实现。
     *
     * @param pattern 模式
     * @param p       模式下标
     * @param name    变量名
     * @param n       名字下标
     * @return 匹配返回 {@code true}
     */
    private static boolean matchIgnoreCase(String pattern, int p, String name, int n) {
        while (p < pattern.length()) {
            char current = pattern.charAt(p);
            if (current == '*') {
                // 星号可以吞掉任意多个字符：逐个后缀尝试
                for (int skip = n; skip <= name.length(); skip++) {
                    if (matchIgnoreCase(pattern, p + 1, name, skip)) {
                        return true;
                    }
                }
                return false;
            }
            if (n >= name.length()) {
                return false;
            }
            if (current != '?' && Character.toLowerCase(current) != Character.toLowerCase(name.charAt(n))) {
                return false;
            }
            p++;
            n++;
        }
        return n == name.length();
    }

    /**
     * 构造防挂死默认变量表。
     *
     * @return 映射
     */
    private static Map<String, String> antiHang() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("PAGER", "cat");
        values.put("GIT_PAGER", "cat");
        values.put("GIT_TERMINAL_PROMPT", "0");
        values.put("TERM", "dumb");
        values.put("NO_COLOR", "1");
        values.put("DEBIAN_FRONTEND", "noninteractive");
        return values;
    }
}
