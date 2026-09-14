package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用请求：ReAct 循环请求执行一次工具调用，由内核按工具名路由。
 * <p>
 * 路由键即工具名，因此每个工具对应一个处理器（{@code PluginContext.handle}）。
 *
 * @author zcd
 */
public final class ToolCallRequest extends ExtensionRequest<ToolCallResult> {

    /** 工具名，也是路由键。 */
    private final String toolName;

    /** 工具参数，只读。 */
    private final Map<String, Object> arguments;

    /**
     * 构造工具调用请求。
     *
     * @param toolName  工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId) {
        super(ToolCallResult.class, sessionId);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * 构造进程级工具调用请求。
     *
     * @param toolName  工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments) {
        this(toolName, arguments, null);
    }

    @Override
    public String getRouteKey() {
        return toolName;
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
     * 获取工具参数。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }
}
