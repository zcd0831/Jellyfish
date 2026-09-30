package zcd.jellyfish.api.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 传输层看到的一条对话消息。
 * <p>
 * <b>为什么不能让插件看到内核的 {@code LlmMessage}</b>：那个类型随厂商持续演化（新的内容块、
 * 新的角色、新的元数据），而它一旦进入 {@code api}，「插件作者唯一的稳定契约」就变成了
 * 「跟着厂商变的契约」。这里的字段是<b>归一化之后的最小交集</b>：任何一家协议都能映射进来，
 * 也都能映射出去。
 * <p>
 * <b>角色是字符串而不是枚举</b>：厂商不只有三种角色（有的有 {@code developer}、有的有
 * {@code tool} 之外的自定义角色），枚举会把「内核没听过的新角色」直接变成一次失败。
 * 取值沿用厂商习惯的写法：{@code system} / {@code user} / {@code assistant} / {@code tool}。
 * <p>
 * <b>{@code reasoning} 是给回传用的</b>：带思考过程的厂商（Anthropic 的 extended thinking、
 * DeepSeek 的 reasoning）要求把上一轮的思考内容原样带回来，否则下一轮会被拒。
 * <b>当前内核出站时恒为 {@code null}</b>——{@code LlmMessage} 本身不携带思考内容，
 * 因此它只是给未来的回传留好的位置；插件照常映射即可，将来不需要改。
 * <p>
 * <b>同一个字段承载两种消息</b>：带 {@code toolCalls} 的是助手发起调用，带 {@code toolCallId}
 * 的是工具结果。两者互斥，但用一个类型表达——协议里的消息本来就只有「一条」这个概念，
 * 拆成两个类型只会让插件的映射多一层分支。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    /** 角色。 */
    private final String role;

    /** 文本内容，可为 {@code null}。 */
    private final String content;

    /** 思考过程，可为 {@code null}。 */
    private final String reasoning;

    /** 本条消息发起的工具调用；工具结果消息为空列表。 */
    private final List<LlmTransportToolCall> toolCalls;

    /** 本条消息回应的工具调用 id；非工具结果消息为 {@code null}。 */
    private final String toolCallId;

    /** 工具名，仅工具结果消息有值，便于端点侧做展示与归因。 */
    private final String toolName;

    /**
     * 构造消息。
     *
     * @param role       角色，不可为空白
     * @param content    文本内容，可为 {@code null}
     * @param reasoning  思考过程，可为 {@code null}
     * @param toolCalls  发起的工具调用，可为 {@code null}
     * @param toolCallId 回应的工具调用 id，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     */
    public LlmTransportMessage(String role, String content, String reasoning,
                               List<LlmTransportToolCall> toolCalls, String toolCallId, String toolName) {
        this.role = role == null || role.trim().isEmpty() ? ROLE_USER : role;
        this.content = content;
        this.reasoning = reasoning;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmTransportToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<LlmTransportToolCall>(toolCalls));
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    /**
     * 构造一条纯文本消息，便于组合 {@code user} / {@code assistant} 消息。
     *
     * @param role    角色
     * @param content 文本内容
     * @return 消息
     */
    public static LlmTransportMessage text(String role, String content) {
        return new LlmTransportMessage(role, content, null, null, null, null);
    }

    /**
     * 获取角色。
     *
     * @return 角色，保证非空白
     */
    public String getRole() {
        return role;
    }

    /**
     * 获取文本内容。
     *
     * @return 文本内容，可能为 {@code null}
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取思考过程。
     *
     * @return 思考过程，可能为 {@code null}
     */
    public String getReasoning() {
        return reasoning;
    }

    /**
     * 获取本条消息发起的工具调用。
     *
     * @return 工具调用，可能为空但不会为 {@code null}
     */
    public List<LlmTransportToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 获取本条消息回应的工具调用 id。
     *
     * @return 工具调用 id，非工具结果消息为 {@code null}
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，可能为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 判断本条消息是否为工具结果。
     *
     * @return 是工具结果时返回 {@code true}
     */
    public boolean isToolResult() {
        return toolCallId != null && !toolCallId.isEmpty();
    }

    @Override
    public String toString() {
        return "LlmTransportMessage{role=" + role
                + ", contentLength=" + (content == null ? 0 : content.length())
                + ", toolCalls=" + toolCalls.size()
                + ", toolCallId=" + toolCallId + '}';
    }
}
