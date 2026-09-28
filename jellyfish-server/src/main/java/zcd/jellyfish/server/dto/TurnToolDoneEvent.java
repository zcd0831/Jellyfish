package zcd.jellyfish.server.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件 {@code tool_done} 的载荷：一次工具调用结束（成功或失败都会来）。
 * <p>
 * {@code output} 是回灌给模型的<b>同一份文本</b>（可能已被 {@code ToolOutputLimiter} 截断成信封）。
 * 前端原样展示即可，不要试图从别处再取一份原始输出——这里就是唯一的真相。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class TurnToolDoneEvent {

    /** 回合标识。 */
    private final String turnId;

    /** 工具调用标识。 */
    private final String toolCallId;

    /** 工具名。 */
    private final String toolName;

    /** 是否成功。 */
    private final boolean success;

    /** 结果文本；失败时为错误说明。 */
    private final String output;

    /**
     * 结构化元数据，保证非 {@code null}（无元数据时为空对象）。
     * <p>
     * 它是 {@code output} 首行那句结论的机器可读版本（退出码、终止原因），前端据此渲染失败标记，
     * 而不必去解析文本——把展示绑在文案格式上，改一个措辞标记就会消失。
     */
    private final Map<String, Object> metadata;

    /**
     * 构造事件。
     *
     * @param turnId     回合标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param success    是否成功
     * @param output     结果文本
     * @param metadata   结构化元数据，可为 {@code null}（等价空对象）
     */
    public TurnToolDoneEvent(String turnId, String toolCallId, String toolName, boolean success, String output,
                             Map<String, Object> metadata) {
        this.turnId = turnId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.success = success;
        this.output = output;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    /**
     * 获取结构化元数据。
     *
     * @return 元数据，保证非 {@code null}，无元数据时为空对象
     */
    public Map<String, Object> getMetadata() {
        return metadata;
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
     * 获取工具调用标识。
     *
     * @return 工具调用标识
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 判断是否成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取结果文本。
     *
     * @return 结果文本，可能为 {@code null}
     */
    public String getOutput() {
        return output;
    }
}
