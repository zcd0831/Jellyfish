package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 扩展点错误码异常：表示「框架判定这次扩展点调用无法正常完成」。
 * <p>
 * 与处理器自身抛出的业务异常严格区分：处理器抛出的异常由内核原样回传给调用者（或由调用点自行处置），
 * 本异常只承载框架侧的判定结果，便于调用点按错误码分支处理。
 *
 * @author zcd
 */
public class ExtensionException extends JellyfishException {

    /** 扩展点错误码。 */
    public enum Code {

        /** 没有任何处理器匹配该请求。 */
        NO_HANDLER,

        /** 唯一入口（{@code handler}）匹配到多个处理器：说明该调用点用错了查找入口。 */
        AMBIGUOUS_HANDLER,

        /** 注册冲突：同键已有处理器且未声明覆盖。 */
        DUPLICATE_HANDLER,

        /** 处理器返回的结果与 {@link ExtensionRequest#getResultType()} 不匹配。 */
        RESULT_TYPE_MISMATCH,

        /** 取出的描述符与调用点期望的类型不匹配。 */
        DESCRIPTOR_TYPE_MISMATCH
    }

    /** 错误码。 */
    private final Code code;

    /**
     * 构造扩展点异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     */
    public ExtensionException(Code code, String detail) {
        super("extension " + code + ": " + detail);
        this.code = code;
    }

    /**
     * 构造带 cause 的扩展点异常。
     *
     * @param code   错误码
     * @param detail 错误细节
     * @param cause  原始异常
     */
    public ExtensionException(Code code, String detail, Throwable cause) {
        super("extension " + code + ": " + detail, cause);
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
