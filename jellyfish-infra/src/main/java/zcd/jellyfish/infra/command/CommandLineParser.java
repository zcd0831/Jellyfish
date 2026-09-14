package zcd.jellyfish.infra.command;

import zcd.jellyfish.api.extension.CommandArguments;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令行解析器：前缀判定 + 命令名提取 + 引号感知切分 + 原文提取。
 * <p>
 * 只认前缀与空白，不查注册表、不认识别名：别名属于注册表事实，由 {@link CommandManager} 解析。
 * <p>
 * 切分口径（刻意保持最小可用）：
 * <ul>
 *     <li>先去掉输入首尾空白，首字符不是 {@link CommandManager#COMMAND_PREFIX} 即为非命令；</li>
 *     <li>命令名 = 前缀之后到第一个空白为止，必须非空，大小写敏感；</li>
 *     <li>{@code raw} = 命令名之后的原文（仅去掉紧随命令名的那段空白），引号与内部空白原样保留；</li>
 *     <li>{@code tokens} 按空白切分，{@code "} 与 {@code '} 成对包裹，引号内空白不切分、引号字符本身不进入
 *     token，{@code ""} 产出一个空 token；双引号内 {@code \"} 与 {@code \\} 转义；</li>
 *     <li>引号未闭合是解析错误：与其猜用户意图（读到行尾？丢掉引号？），不如当场报错。</li>
 * </ul>
 *
 * @author zcd
 */
final class CommandLineParser {

    /** 双引号字符。 */
    private static final char DOUBLE_QUOTE = '"';

    /** 单引号字符。 */
    private static final char SINGLE_QUOTE = '\'';

    /** 转义字符，仅在双引号内生效。 */
    private static final char ESCAPE = '\\';

    private CommandLineParser() {
    }

    /**
     * 解析一行输入。
     *
     * @param input 用户输入原文，可为 {@code null}
     * @return 解析结果，保证非 {@code null}
     */
    static ParsedCommand parse(String input) {
        if (input == null) {
            return ParsedCommand.notCommand();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith(CommandManager.COMMAND_PREFIX)) {
            return ParsedCommand.notCommand();
        }
        int nameEnd = indexOfWhitespace(trimmed, CommandManager.COMMAND_PREFIX.length());
        String name = nameEnd < 0
                ? trimmed.substring(CommandManager.COMMAND_PREFIX.length())
                : trimmed.substring(CommandManager.COMMAND_PREFIX.length(), nameEnd);
        if (name.isEmpty()) {
            // 只有前缀没有名字：这是「不是命令」，而不是「未知命令」
            return ParsedCommand.notCommand();
        }
        String tail = nameEnd < 0 ? "" : trimmed.substring(nameEnd).trim();
        return arguments(name, tail);
    }

    /**
     * 切分命令名之后的原文。
     *
     * @param name 命令名
     * @param tail 命令名之后的原文（已去掉紧随命令名的那段空白）
     * @return 解析结果：引号未闭合时为带错误的命令
     */
    private static ParsedCommand arguments(String name, String tail) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean started = false;
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < tail.length(); i++) {
            char current = tail.charAt(i);
            if (inDouble) {
                if (current == ESCAPE && i + 1 < tail.length() && isEscapable(tail.charAt(i + 1))) {
                    token.append(tail.charAt(i + 1));
                    i++;
                    continue;
                }
                if (current == DOUBLE_QUOTE) {
                    inDouble = false;
                    continue;
                }
                token.append(current);
                continue;
            }
            if (inSingle) {
                if (current == SINGLE_QUOTE) {
                    inSingle = false;
                    continue;
                }
                token.append(current);
                continue;
            }
            if (current == DOUBLE_QUOTE) {
                inDouble = true;
                // 进入引号即视为「这个 token 已经开始」："" 要产出空 token，而不是被当成没有参数
                started = true;
                continue;
            }
            if (current == SINGLE_QUOTE) {
                inSingle = true;
                started = true;
                continue;
            }
            if (Character.isWhitespace(current)) {
                if (started) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
                continue;
            }
            token.append(current);
            started = true;
        }
        if (inDouble || inSingle) {
            return ParsedCommand.invalid(name, "引号未闭合：" + tail);
        }
        if (started) {
            tokens.add(token.toString());
        }
        return ParsedCommand.of(name, new CommandArguments(tokens, tail));
    }

    /**
     * 判断字符在双引号内是否可被转义。
     *
     * @param candidate 待判定字符
     * @return 可转义返回 {@code true}
     */
    private static boolean isEscapable(char candidate) {
        return candidate == DOUBLE_QUOTE || candidate == ESCAPE;
    }

    /**
     * 从起始位置查找第一个空白字符。
     *
     * @param text  待查找文本
     * @param start 起始下标
     * @return 空白字符下标，没有时返回 {@code -1}
     */
    private static int indexOfWhitespace(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
