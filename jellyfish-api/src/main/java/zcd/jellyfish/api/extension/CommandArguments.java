package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令参数：一行输入里「命令名之后」的部分，两种视图并存。
 * <p>
 * {@code tokens} 给结构化命令用（{@code /agent coder} → {@code [coder]}），{@code raw} 给自由文本命令用
 * （{@code /note 记得明天改配置}）：只给 tokens 会逼处理器把参数重新拼回去，而拼接必然丢信息
 * （连续多个空格、原引号形态）。
 * <p>
 * 两种调用路径共用本类型：原文入口（输入框）由内核切分后填充，结构化入口（Web / TUI 直接调用）
 * 由调用方填充、不经过切分——处理器因此只有一种读取姿势。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandArguments {

    /** 空参数：无 token、空原文。 */
    public static final CommandArguments EMPTY = new CommandArguments(null, null);

    /** 切分后的参数，不含命令名。 */
    private final List<String> tokens;

    /** 命令名之后的原文（仅去掉紧随命令名的那段空白），空参数时为空串。 */
    private final String raw;

    /**
     * 构造命令参数。
     *
     * @param tokens 切分结果，可为 {@code null}（等价空列表）
     * @param raw    原文，可为 {@code null}（等价空串）
     * @throws JellyfishException token 列表里存在 {@code null} 元素时抛出
     */
    public CommandArguments(List<String> tokens, String raw) {
        this.tokens = copyTokens(tokens);
        this.raw = raw == null ? "" : raw;
    }

    /**
     * 获取切分后的参数。
     *
     * @return 不可变 token 列表，保证非 {@code null}
     */
    public List<String> getTokens() {
        return tokens;
    }

    /**
     * 获取原文。
     *
     * @return 原文，保证非 {@code null}（可能为空串）
     */
    public String getRaw() {
        return raw;
    }

    /**
     * 判断是否没有参数。
     *
     * @return 无 token 返回 {@code true}
     */
    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /**
     * 获取 token 数量。
     *
     * @return token 数量
     */
    public int size() {
        return tokens.size();
    }

    @Override
    public String toString() {
        return "CommandArguments{tokens=" + tokens + ", raw=" + raw + '}';
    }

    /**
     * 复制 token 列表并拒绝 {@code null} 元素。
     * <p>
     * {@code null} 元素只可能来自结构化入口的调用方（切分器不会产出），属于编程错误，
     * 当场抛出远比让处理器在别处 NPE 更好排查。
     *
     * @param tokens 原始 token 列表，可为 {@code null}
     * @return 不可变副本
     * @throws JellyfishException 存在 {@code null} 元素时抛出
     */
    private static List<String> copyTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<String>(tokens.size());
        for (String token : tokens) {
            if (token == null) {
                throw new JellyfishException("command token must not be null");
            }
            copy.add(token);
        }
        return Collections.unmodifiableList(copy);
    }
}
