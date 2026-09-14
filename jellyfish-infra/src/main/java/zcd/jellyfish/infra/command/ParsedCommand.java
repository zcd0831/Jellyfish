package zcd.jellyfish.infra.command;

import zcd.jellyfish.api.extension.CommandArguments;

/**
 * 一行输入的解析结果：是否命令 / 命令名 / 参数 / 解析错误。
 * <p>
 * 包私有：切分规则是内核实现细节，插件只消费 {@link CommandArguments}，不感知解析过程。
 * <p>
 * 不可变，仅由 {@link CommandLineParser} 构造。
 *
 * @author zcd
 */
final class ParsedCommand {

    /** 非命令的单例结果。 */
    private static final ParsedCommand NOT_COMMAND =
            new ParsedCommand(false, null, CommandArguments.EMPTY, null);

    /** 语法上是不是命令（前缀 + 非空命令名）。 */
    private final boolean command;

    /** 命令名（不含前缀），非命令时为 {@code null}。 */
    private final String name;

    /** 解析出的参数，非命令时为空参数。 */
    private final CommandArguments arguments;

    /** 解析错误文案，无错误时为 {@code null}。 */
    private final String error;

    /**
     * 构造解析结果。
     *
     * @param command   是否命令
     * @param name      命令名，可为 {@code null}
     * @param arguments 参数，不可为 {@code null}
     * @param error     解析错误文案，可为 {@code null}
     */
    private ParsedCommand(boolean command, String name, CommandArguments arguments, String error) {
        this.command = command;
        this.name = name;
        this.arguments = arguments;
        this.error = error;
    }

    /**
     * 构造「不是命令」结果。
     *
     * @return 非命令结果
     */
    static ParsedCommand notCommand() {
        return NOT_COMMAND;
    }

    /**
     * 构造「是命令」结果。
     *
     * @param name      命令名，不可为空白
     * @param arguments 参数，不可为 {@code null}
     * @return 解析成功的命令
     */
    static ParsedCommand of(String name, CommandArguments arguments) {
        return new ParsedCommand(true, name, arguments, null);
    }

    /**
     * 构造「是命令但参数有语法错误」结果。
     * <p>
     * 仍然是命令（前缀与非空命令名都在），只是参数读不出来——这样调用点能区分
     * 「这行不是命令」与「这条命令的参数写错了」。
     *
     * @param name  命令名，不可为空白
     * @param error 解析错误文案，不可为空白
     * @return 带错误的命令
     */
    static ParsedCommand invalid(String name, String error) {
        return new ParsedCommand(true, name, CommandArguments.EMPTY, error);
    }

    /**
     * 判断语法上是不是命令。
     *
     * @return 是命令返回 {@code true}
     */
    boolean isCommand() {
        return command;
    }

    /**
     * 获取命令名。
     *
     * @return 命令名（不含前缀），非命令时为 {@code null}
     */
    String name() {
        return name;
    }

    /**
     * 获取参数。
     *
     * @return 参数，保证非 {@code null}
     */
    CommandArguments arguments() {
        return arguments;
    }

    /**
     * 获取解析错误文案。
     *
     * @return 错误文案，无错误时为 {@code null}
     */
    String error() {
        return error;
    }
}
