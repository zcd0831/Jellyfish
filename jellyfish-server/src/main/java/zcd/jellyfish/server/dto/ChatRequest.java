package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /sessions/{id}/chat} 的请求体。
 * <p>
 * 只有一条用户输入：会话、模型与 agent 都在会话上，不需要每个回合重复携带。
 * 空白的 {@code message} 由处理器判为 400，而不是在这里构造期抛——那位「给用户一个可读的接口错误」，
 * 不是「给程序员一个断言」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ChatRequest {

    /** 用户输入。 */
    private final String message;

    /**
     * 构造请求。
     *
     * @param message 用户输入，可为 {@code null}
     */
    @JsonCreator
    public ChatRequest(@JsonProperty("message") String message) {
        this.message = message;
    }

    /**
     * 获取用户输入。
     *
     * @return 用户输入，可能为 {@code null}
     */
    public String getMessage() {
        return message;
    }
}
