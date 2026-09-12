package zcd.jellyfish.infra.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 LLM 调用的统一返回结果。不可变，构造时对工具调用列表做防御性拷贝。
 *
 * @author zcd
 */
public final class LlmResponse {

    /** 模型的文本回复，无文本内容时为 {@code null}。 */
    private final String content;

    /** 思考过程（reasoning / thinking），不支持或未开启时为 {@code null}。 */
    private final String thinking;

    /** 模型请求调用的工具，无工具调用时为空列表。 */
    private final List<LlmToolCall> toolCalls;

    /** token 使用量，厂商未返回时为 {@code null}。 */
    private final LlmUsage usage;

    /** 结束原因，取值由各厂商定义。 */
    private final String finishReason;

    /**
     * 构造返回结果。
     *
     * @param content      文本回复
     * @param thinking     思考过程
     * @param toolCalls    工具调用，可为 {@code null}
     * @param usage        token 使用量，可为 {@code null}
     * @param finishReason 结束原因
     */
    public LlmResponse(String content, String thinking, List<LlmToolCall> toolCalls, LlmUsage usage, String finishReason) {
        this.content = content;
        this.thinking = thinking;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.usage = usage;
        this.finishReason = finishReason;
    }

    /**
     * 构造一个仅含文本的简单结果，便于测试与占位场景使用。
     *
     * @param content 文本回复
     * @return 仅含文本的返回结果
     */
    public static LlmResponse text(String content) {
        return new LlmResponse(content, null, null, null, null);
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
     * 获取思考过程（reasoning / thinking）。
     *
     * @return 思考过程，不支持或未开启时为 {@code null}
     */
    public String getThinking() {
        return thinking;
    }

    /**
     * 获取工具调用列表。
     *
     * @return 工具调用，可能为空但不会为 {@code null}
     */
    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 获取 token 使用量。
     *
     * @return token 使用量，厂商未返回时为 {@code null}
     */
    public LlmUsage getUsage() {
        return usage;
    }

    /**
     * 获取结束原因。
     *
     * @return 结束原因，厂商未返回时为 {@code null}
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * 判断本次返回是否包含工具调用。
     *
     * @return 存在工具调用时返回 {@code true}
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 返回本次调用的可读表示，便于日志排查。
     *
     * @return 描述字符串
     */
    @Override
    public String toString() {
        return "LlmResponse{" +
                "content='" + content + '\'' +
                ", thinking='" + thinking + '\'' +
                ", toolCalls=" + toolCalls +
                ", usage=" + usage +
                ", finishReason='" + finishReason + '\'' +
                '}';
    }
}
