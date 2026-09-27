package zcd.jellyfish.server.dto;

/**
 * SSE 事件 {@code error} 的载荷：回合因异常终止。
 * <p>
 * 与 HTTP 层面的 {@link ApiErrorDto} 同名同形（{@code code} + {@code message}），但语义不同：
 * 它发生在流已经开始之后，此时 HTTP 状态码已经发出（恒为 200），错误只能由流内的事件表达。
 * <b>错误码用同一套常量</b>，前端对两种来源可以写同一段分支。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnErrorEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 稳定错误码。 */
    private final String code;

    /** 可读说明。 */
    private final String message;

    /**
     * 构造事件。
     *
     * @param turnId  回合标识
     * @param code    稳定错误码
     * @param message 可读说明
     */
    public TurnErrorEvent(String turnId, String code, String message) {
        this.turnId = turnId;
        this.code = code;
        this.message = message;
    }

    /**
     * 获取回合标识。
     *
     * @return 回合标识
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取可读说明。
     *
     * @return 说明，可能为 {@code null}
     */
    public String getMessage() {
        return message;
    }
}
