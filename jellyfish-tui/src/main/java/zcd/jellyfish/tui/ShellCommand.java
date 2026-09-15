package zcd.jellyfish.tui;

/**
 * 外壳自有命令：用户与外壳之间的约定，不进内核的命令注册表。
 * <p>
 * <b>为什么只有 {@code /exit}</b>：{@code /help}、{@code /new}、{@code /model} 这些属于内核命令域，
 * 由 {@code CommandManager} 统一解析与分发，外壳只负责把结果贴到屏幕上。而「退出界面」是外壳自己的事——
 * 内核根本不知道有没有界面，把它注册成命令会让 {@code /help} 里出现一条对 {@code -cli} 模式毫无意义的条目。
 * 这与 {@code core/command/SystemCommands} 里「{@code /exit} 属外壳职责，不进注册表」的口径一致。
 * <p>
 * <b>判定必须早于 {@code CommandManager.execute}</b>：一旦把它交给命令域，就会以
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

    private ShellCommand() {
    }

    /**
     * 判断输入是否为外壳自有命令。
     * <p>
     * 只做首词匹配：{@code /exit} 后面跟什么参数都算退出，避免用户敲了
     * {@code /exit now} 却得到一条「未知命令」。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是外壳命令返回 {@code true}
     */
    public static boolean isShellCommand(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        return EXIT.equals(head) || QUIT.equals(head);
    }
}
