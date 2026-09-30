package zcd.jellyfish.api.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 传输层返回的一次调用的完整结果。
 * <p>
 * <b>它只出现在终止事件里</b>：{@link LlmTransportListener#onComplete} 拿到的这一个对象就是本次调用的
 * 全集——工具调用以这里为准，流式途中的分片快照只是过程。这条口径让「拼装」只发生在插件内部，
 * 内核拿到的永远是最终值。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportResponse {

    /** 文本回复，无文本时为 {@code null}。 */
    private final String content;

    /** 思考过程，不支持或未开启时为 {@code null}。 */
    private final String reasoning;

    /** 本次请求的工具调用。 */
    private final List<LlmTransportToolCall> toolCalls;

    /** token 用量，厂商未返回时为 {@code null}。 */
    private final LlmTransportUsage usage;

    /** 结束原因，取值由各厂商定义。 */
    private final String finishReason;

    /**
     * 构造结果。
     *
     * @param content      文本回复，可为 {@code null}
     * @param reasoning    思考过程，可为 {@code null}
     * @param toolCalls    工具调用，可为 {@code null}
     * @param usage        token 用量，可为 {@code null}
     * @param finishReason 结束原因，可为 {@code null}
     */
    public LlmTransportResponse(String content, String reasoning, List<LlmTransportToolCall> toolCalls,
                                LlmTransportUsage usage, String finishReason) {
        this.content = content;
        this.reasoning = reasoning;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmTransportToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmTransportToolCall>(toolCalls));
        this.usage = usage;
        this.finishReason = finishReason;
    }

    /**
     * 构造一个仅含文本的简单结果，便于占位场景与测试使用。
     *
     * @param content 文本回复
     * @return 仅含文本的结果
     */
    public static LlmTransportResponse text(String content) {
        return new LlmTransportResponse(content, null, null, null, null);
    }

    /**
     * 获取文本回复。
     *
     * @return 文本回复，无文本时为 {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取思考过程。
     *
     * @return 思考过程，可为 {@code null}
     */
    public String getReasoning() {
        return reasoning;
    }

    /**
     * 获取工具调用。
     *
     * @return 工具调用，可能为空但不会为 {@code null}
     */
    public List<LlmTransportToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 获取 token 用量。
     *
     * @return token 用量，厂商未返回时为 {@code null}
     */
    public LlmTransportUsage getUsage() {
        return usage;
    }

    /**
     * 获取结束原因。
     *
     * @return 结束原因，可能为 {@code null}
     */
    public String getFinishReason() {
        return finishReason;
    }

    @Override
    public String toString() {
        return "LlmTransportResponse{contentLength=" + (content == null ? 0 : content.length())
                + ", toolCalls=" + toolCalls.size() + ", usage=" + usage
                + ", finishReason=" + finishReason + '}';
    }
}
