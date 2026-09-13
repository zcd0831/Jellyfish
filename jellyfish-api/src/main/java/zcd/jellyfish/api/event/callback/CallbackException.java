package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.JellyfishException;

/**
 * 回调通道错误码异常。
 * <p>
 * 与处理器自身抛出的业务异常区分开：本异常表示「框架判定回调无法正常完成」，
 * 处理器返回的业务异常则原样回传给调用者。
 *
 * @author zcd
 */
public class CallbackException extends JellyfishException {

    /** 回调错误码。 */
    public enum Code {

        /** 没有任何处理器匹配该回调。 */
        NO_HANDLER,

        /** 多于一个处理器匹配该回调，路由不唯一。 */
        AMBIGUOUS_HANDLER,

        /** 回调走完全程但应答槽未被填充。 */
        NO_RESPONSE,

        /** 处理器返回的结果与 {@code resultType} 不匹配。 */
        RESULT_TYPE_MISMATCH,

        /** 回调嵌套深度超过上限。 */
        NESTING_TOO_DEEP
    }

    /** 错误码。 */
    private final Code code;

    /**
     * 构造回调异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     */
    public CallbackException(Code code, String detail) {
        super("callback " + code + ": " + detail);
        this.code = code;
    }

    /**
     * 构造带 cause 的回调异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     * @param cause  原始异常
     */
    public CallbackException(Code code, String detail, Throwable cause) {
        super("callback " + code + ": " + detail, cause);
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
