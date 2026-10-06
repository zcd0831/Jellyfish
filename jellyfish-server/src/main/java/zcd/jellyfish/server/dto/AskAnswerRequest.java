package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /asks/{requestId}} 的请求体。
 * <p>
 * <b>两种作答形状，任选其一</b>：{@code optionId} 表示「选了某个候选项」，
 * {@code text} 表示「用户自己填了一个答案」。两个都缺是请求写错了（400），
 * 两个都给则以 {@code optionId} 为准（它表达的是更明确的意图）。
 * <p>
 * <b>为什么不用一个字段加判别式</b>：这两种答案在客户端的来源本就不同——一个是点选，
 * 一个是文本框。分两个字段让「哪种作答」不必靠约定推断，也让两个都缺这件事能当场被判成错误。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskAnswerRequest {

    /** 用户选中的选项标识；{@code null} 表示未提供。 */
    private final String optionId;

    /** 用户自己填的答案原文；{@code null} 表示未提供。 */
    private final String text;

    /**
     * 构造请求。
     *
     * @param optionId 用户选中的选项标识，可为 {@code null}
     * @param text     用户自己填的答案原文，可为 {@code null}
     */
    @JsonCreator
    public AskAnswerRequest(@JsonProperty("optionId") String optionId, @JsonProperty("text") String text) {
        this.optionId = optionId;
        this.text = text;
    }

    /**
     * 获取用户选中的选项标识。
     *
     * @return 选项标识，可能为 {@code null}
     */
    public String getOptionId() {
        return optionId;
    }

    /**
     * 获取用户自己填的答案原文。
     *
     * @return 答案原文，可能为 {@code null}
     */
    public String getText() {
        return text;
    }
}
