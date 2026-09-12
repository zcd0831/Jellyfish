package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 与各厂商接口无关的统一消息模型。不可变，构造时对工具调用列表做防御性拷贝。
 *
 * <ul>
 *     <li>{@link #ROLE_SYSTEM} 系统提示词</li>
 *     <li>{@link #ROLE_USER} 用户消息</li>
 *     <li>{@link #ROLE_ASSISTANT} 模型回复，可能携带 {@link LlmToolCall}</li>
 *     <li>{@link #ROLE_TOOL} 工具执行结果，通过 {@code toolCallId} 与调用关联</li>
 * </ul>
 *
 * @author zcd
 */
public final class LlmMessage {

    /** 系统提示词角色。 */
    public static final String ROLE_SYSTEM = "system";

    /** 用户消息角色。 */
    public static final String ROLE_USER = "user";

    /** 模型回复角色。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 工具执行结果角色。 */
    public static final String ROLE_TOOL = "tool";

    /** 消息角色，取值见本类的 {@code ROLE_*} 常量。 */
    private final String role;

    /** 文本内容，携带工具调用的模型回复可能为空。 */
    private final String content;

    /** 工具结果消息关联的工具调用 id，仅 {@link #ROLE_TOOL} 使用。 */
    private final String toolCallId;

    /** 工具结果消息对应的工具名，部分厂商（Gemini）需要。 */
    private final String name;

    /** 模型回复携带的工具调用，仅 {@link #ROLE_ASSISTANT} 可能非空。 */
    private final List<LlmToolCall> toolCalls;

    /**
     * 构造一条消息。
     *
     * @param role       消息角色，不可为空
     * @param content    文本内容
     * @param toolCallId 关联的工具调用 id，仅工具结果消息使用
     * @param name       工具名，仅工具结果消息使用
     * @param toolCalls  模型回复携带的工具调用，可为 {@code null}
     */
    public LlmMessage(String role, String content, String toolCallId, String name, List<LlmToolCall> toolCalls) {
        if (role == null || role.trim().isEmpty()) {
            throw new JellyfishException("message role must not be blank");
        }
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.name = name;
        this.toolCalls = toolCalls == null
                ? Collections.<LlmToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }

    /**
     * 构造系统提示词消息。
     *
     * @param content 提示词内容
     * @return 系统消息
     */
    public static LlmMessage system(String content) {
        return new LlmMessage(ROLE_SYSTEM, content, null, null, null);
    }

    /**
     * 构造用户消息。
     *
     * @param content 用户输入
     * @return 用户消息
     */
    public static LlmMessage user(String content) {
        return new LlmMessage(ROLE_USER, content, null, null, null);
    }

    /**
     * 构造不含工具调用的模型回复消息。
     *
     * @param content 模型文本回复
     * @return 模型回复消息
     */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(ROLE_ASSISTANT, content, null, null, null);
    }

    /**
     * 构造携带工具调用的模型回复消息。
     *
     * @param content   模型文本回复，可为 {@code null}
     * @param toolCalls 模型请求调用的工具
     * @return 模型回复消息
     */
    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(ROLE_ASSISTANT, content, null, null, toolCalls);
    }

    /**
     * 构造工具执行结果消息。
     *
     * @param toolCallId 对应的工具调用 id
     * @param name       工具名
     * @param content    工具执行结果文本
     * @return 工具结果消息
     */
    public static LlmMessage tool(String toolCallId, String name, String content) {
        return new LlmMessage(ROLE_TOOL, content, toolCallId, name, null);
    }

    /**
     * 获取消息角色。
     *
     * @return 消息角色
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
     * 获取关联的工具调用 id。
     *
     * @return 工具调用 id，仅工具结果消息非空
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，仅工具结果消息非空
     */
    public String getName() {
        return name;
    }

    /**
     * 获取模型回复携带的工具调用。
     *
     * @return 工具调用，可能为空但不会为 {@code null}
     */
    public List<LlmToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * 判断本条消息是否携带工具调用。
     *
     * @return 存在工具调用时返回 {@code true}
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
