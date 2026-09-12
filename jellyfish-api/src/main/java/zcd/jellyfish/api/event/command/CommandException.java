package zcd.jellyfish.api.event.command;

import zcd.jellyfish.api.JellyfishException;

/**
 * 命令通道错误码异常。
 * <p>
 * 与处理器自身抛出的业务异常区分开：本异常表示「框架判定命令无法正常完成」，
 * 处理器返回的业务异常则原样回传给调用者。
 *
 * @author zcd
 */
public class CommandException extends JellyfishException {

    /** 命令错误码。 */
    public enum Code {

        /** 没有任何处理器匹配该命令。 */
        NO_HANDLER,

        /** 多于一个处理器匹配该命令，路由不唯一。 */
        AMBIGUOUS_HANDLER,

        /** 命令走完全程但应答槽未被填充。 */
        NO_RESPONSE,

        /** 处理器返回的结果与 {@code resultType} 不匹配。 */
        RESULT_TYPE_MISMATCH,

        /** 命令嵌套深度超过上限。 */
        NESTING_TOO_DEEP
    }

    /** 错误码。 */
    private final Code code;

    /**
     * 构造命令异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     */
    public CommandException(Code code, String detail) {
        super("command " + code + ": " + detail);
        this.code = code;
    }

    /**
     * 构造带 cause 的命令异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     * @param cause  原始异常
     */
    public CommandException(Code code, String detail, Throwable cause) {
        super("command " + code + ": " + detail, cause);
        this.code = code;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public Code getCode() {
        return code;
    }
}
