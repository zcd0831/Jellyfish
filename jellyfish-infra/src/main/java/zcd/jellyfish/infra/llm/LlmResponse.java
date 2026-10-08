package zcd.jellyfish.infra.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一次 LLM 调用的统一返回结果。不可变，构造时对工具调用列表做防御性拷贝。
 *
 * @author zcd
 */
public final class LlmResponse {

    /**
     * 各厂商表示「输出被上限截断」的结束原因取值（已小写归一）。
     * <p>
     * OpenAI 系是 {@code length}；Anthropic 的 {@code stop_reason} 是 {@code max_tokens}；
     * Gemini 的 {@code finishReason} 是 {@code MAX_TOKENS}——<b>小写之后后两者恰好同一个词</b>，
     * 因此这里只留两个取值。内核不能把原文当判据散在各处，那会让「哪个厂商叫什么」变成调用方要记住的知识。
     */
    private static final Set<String> TRUNCATION_REASONS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("length", "max_tokens")));

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
     * 本次回复是否<b>不完整</b>（流在结束标记之前就断了）。
     * <p>
     * <b>为什么它与「被上限截断」分开记</b>：两者的成因不同（一个是配小了输出上限，一个是连接被切断），
     * 用户能采取的行动也不同。但对外都归入 {@link #isTruncated()}——调用方要判断的是
     * 「这段回复能不能当成完整答案」，而不是成因。
     */
    private final boolean incomplete;

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
        this(content, thinking, toolCalls, usage, finishReason, false);
    }

    /**
     * 构造返回结果。
     *
     * @param content      文本回复
     * @param thinking     思考过程
     * @param toolCalls    工具调用，可为 {@code null}
     * @param usage        token 使用量，可为 {@code null}
     * @param finishReason 结束原因
     * @param incomplete   流是否在结束标记之前断掉
     */
    private LlmResponse(String content, String thinking, List<LlmToolCall> toolCalls, LlmUsage usage,
                        String finishReason, boolean incomplete) {
        this.content = content;
        this.thinking = thinking;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.usage = usage;
        this.finishReason = finishReason;
        this.incomplete = incomplete;
    }

    /**
     * 返回一个「标记为不完整」的副本。
     * <p>
     * <b>为什么用副本而不是让解码器自己填</b>：只有读流的那一层知道「有没有读到结束标记」，
     * 解码器（各厂商一份）不该为此各加一个参数字段——那会让同一件事在三处实现，而漏掉一处的后果
     * 正是「半句话被当成完整答案」。
     *
     * @return 标记为不完整的副本
     */
    public LlmResponse asIncomplete() {
        return incomplete ? this : new LlmResponse(content, thinking, toolCalls, usage, finishReason, true);
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
     * 判断一个厂商给的结束原因是否表示「被输出上限截断」。
     * <p>
     * <b>为什么公开这个判据</b>：「哪个厂商把截断叫什么」这份知识属于本类（见
     * {@code TRUNCATION_REASONS}）。调用方（例如要给用户写提示的那一层）需要区分
     * 「截断」与「流被切断」两种成因，若让它自己再抄一份取值表，两处迟早不一致——
     * 而不一致的后果是把一次正常回复说成截断，或反过来。
     *
     * @param finishReason 结束原因，可为 {@code null}
     * @return 属于截断类返回 {@code true}
     */
    public static boolean isOutputLimitReason(String finishReason) {
        return finishReason != null
                && TRUNCATION_REASONS.contains(finishReason.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 判断本次回复是否不完整（不能当成完整答案）。
     * <p>
     * <b>为什么内核需要它</b>：不完整的回复在形状上与「正常答完」完全一样——有正文、没有工具调用，
     * 因此会被当成一次正常收敛，而屏幕上留下的是一句没说完的话。<b>用户没有任何办法分辨</b>。
     * <p>
     * 两种成因都算：厂商给了截断类的结束原因（配小了输出上限，见 {@link #TRUNCATION_REASONS}），
     * 或流在结束标记之前就断了（{@link #isIncomplete()}）。两者的区别只影响给用户的那句话怎么措辞。
     * <p>
     * 结束原因不认识时返回 {@code false}：宁可少提示，也不要凭猜测把一次正常回复说成截断。
     *
     * @return 回复不完整时返回 {@code true}
     */
    public boolean isTruncated() {
        return isIncomplete() || isOutputLimitReason(finishReason);
    }

    /**
     * 判断流是否在<b>结束标记之前</b>断掉（没读到 {@code [DONE]} / {@code message_stop}）。
     * <p>
     * 与 {@link #isTruncated()} 分开暴露，是为了让提示措辞能对上成因：这一种不是「输出上限配小了」，
     * 因此不该给出「调大 maxOutputTokens」的建议。
     *
     * @return 流被提前切断时返回 {@code true}
     */
    public boolean isIncomplete() {
        return incomplete;
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
