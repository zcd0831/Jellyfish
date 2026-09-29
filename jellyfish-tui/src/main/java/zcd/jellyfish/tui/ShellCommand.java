package zcd.jellyfish.tui;

/**
 * 外壳自有命令：用户与外壳之间的约定，不进内核的命令注册表。
 * <p>
 * <b>为什么只有 {@code /exit}、{@code /ui}、{@code /thinking} 与 {@code /mouse}</b>：{@code /help}、{@code /new}、{@code /model} 这些属于内核命令域，
 * 由 {@code CommandManager} 统一解析与分发，外壳只负责把结果贴到屏幕上。而「退出界面」、「切换插件界面贡献」、
 * 「折叠思考过程」与「交还 / 收回鼠标」是外壳自己的事——内核根本不知道有没有界面、没有区域与折叠这个概念，
 * 也不知道鼠标是被应用捕获还是被终端管着，把它们注册成命令会让 {@code /help} 里出现对 {@code -cli} 模式毫无意义的条目。
 * 这与 {@code core/command/SystemCommands} 里「{@code /exit} 属外壳职责，不进注册表」的口径一致。
 * <p>
 * <b>判定必须早于 {@code CommandManager.execute}</b>：一旦把它们交给命令域，就会以
 * {@code UNKNOWN} 的形式回到屏幕上，用户看到的是「没有这条命令」——而实际上外壳完全听得懂。
 *
 * @author zcd
 */
public final class ShellCommand {

    /** 退出命令名（不含前缀），供外壳补全清单使用。 */
    public static final String EXIT_NAME = "exit";

    /** 退出命令名。 */
    public static final String EXIT = "/" + EXIT_NAME;

    /** 退出命令的别名。 */
    private static final String QUIT = "/quit";

    /** 思考折叠开关命令名（不含前缀），供外壳补全清单使用。 */
    public static final String THINKING_NAME = "thinking";

    /** 思考折叠开关命令。 */
    public static final String THINKING = "/" + THINKING_NAME;

    private ShellCommand() {
    }

    /**
     * 判断输入是否为外壳自有命令（{@code /exit} / {@code /quit} / {@code /ui} / {@code /thinking} / {@code /mouse}）。
     * <p>
     * 只做首词匹配：{@code /exit}、{@code /ui dock} 后面跟什么参数都算命中，避免用户敲了
     * {@code /exit now} 却得到一条「未知命令」。具体分派由调用方按名字做。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是外壳命令返回 {@code true}
     */
    public static boolean isShellCommand(String input) {
        return isExitCommand(input) || isThinkingCommand(input)
                || UiCommand.isUi(input) || MouseCommand.isMouse(input);
    }

    /**
     * 判断输入是否为思考过程折叠开关命令。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是开关命令返回 {@code true}
     */
    public static boolean isThinkingCommand(String input) {
        return THINKING.equals(headOf(input));
    }

    /**
     * 判断输入是否为退出命令。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是退出命令返回 {@code true}
     */
    public static boolean isExitCommand(String input) {
        String head = headOf(input);
        return EXIT.equals(head) || QUIT.equals(head);
    }

    /**
     * 取输入的首词（带命令前缀），供各命令名比对。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 首词（含 {@code /}）；输入为 {@code null} 或空白时返回 {@code null}
     */
    private static String headOf(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
