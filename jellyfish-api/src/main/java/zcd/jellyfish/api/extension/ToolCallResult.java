package zcd.jellyfish.api.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用结果。
 * <p>
 * 处理器失败时直接抛异常，因此结果对象只承载成功语义；{@code output} 由具体工具决定，
 * 通常为字符串或可序列化为 JSON 的对象。
 * <p>
 * <b>元数据是给界面与审计的结构化事实，不是给模型的内容</b>：模型看到的仍是 {@code output}
 * 那段文本（首行写结论），而 {@code metadata} 让界面不必去解析那一行文案就能知道
 * 「命令成没成」（见 {@link ToolMetadata}）。它<b>不进 {@code LlmMessage}</b>——
 * 那是要发给厂商的请求模型，添一个厂商不认识的东西只会污染语义边界。
 *
 * @author zcd
 */
public final class ToolCallResult {

    /** 工具名。 */
    private final String toolName;

    /** 工具输出，可为 {@code null}。 */
    private final Object output;

    /** 结构化元数据，保证非 {@code null}（无元数据时为空映射）。 */
    private final Map<String, Object> metadata;

    /**
     * 构造不带元数据的工具调用结果。
     *
     * @param toolName 工具名
     * @param output   工具输出，可为 {@code null}
     */
    public ToolCallResult(String toolName, Object output) {
        this(toolName, output, null);
    }

    /**
     * 构造带元数据的工具调用结果。
     *
     * @param toolName 工具名
     * @param output   工具输出，可为 {@code null}
     * @param metadata 结构化元数据，可为 {@code null}（等价空映射）
     */
    public ToolCallResult(String toolName, Object output, Map<String, Object> metadata) {
        this.toolName = toolName;
        this.output = output;
        this.metadata = metadata == null || metadata.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
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
     * 获取工具输出。
     *
     * @return 工具输出，可能为 {@code null}
     */
    public Object getOutput() {
        return output;
    }

    /**
     * 获取结构化元数据。
     * <p>
     * 构造期做了防御性拷贝，因此返回的映射不可变、可安全跨线程传递；没有元数据时返回空映射
     * 而不是 {@code null}，调用方不必到处判空。
     *
     * @return 元数据，保证非 {@code null}
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ToolCallResult{toolName=" + toolName + ", output=" + output + ", metadata=" + metadata + '}';
    }
}
