package zcd.jellyfish.server.dto;

/**
 * 错误响应体：{@code {"error": "CODE", "message": "..."}}。
 * <p>
 * <b>为什么错误码与说明分开</b>：前端需要按机器可读的 {@code error} 分支（例如遇到
 * {@code SESSION_NOT_FOUND} 就回列表页），而 {@code message} 是给人看的、可能随文案调整而变化。
 * 把两者合成一个字符串会让前端去匹配中文或英文片段，那是最脆弱的一种契约。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ApiErrorDto {

    /** 稳定错误码。 */
    private final String error;

    /** 可读说明，可为 {@code null}。 */
    private final String message;

    /**
     * 构造错误响应体。
     *
     * @param error   稳定错误码，不可为空白
     * @param message 可读说明，可为 {@code null}
     */
    public ApiErrorDto(String error, String message) {
        this.error = error;
        this.message = message;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 错误码
     */
    public String getError() {
        return error;
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
