package zcd.jellyfish.api.event.callback;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用回调：ReAct 循环请求执行一次工具调用，由插件管理器按工具名路由。
 * <p>
 * 路由键即工具名，因此每个工具对应一个处理器。
 *
 * @author zcd
 */
@PluginExtensible
@ExtensionPoint(id = "tool.provide", shape = ExtensionShape.PROVIDE)
public final class ToolCallRequest extends Callback<ToolCallResult> {

    /** 工具名，也是路由键。 */
    private final String toolName;

    /** 工具参数，只读。 */
    private final Map<String, Object> arguments;

    /**
     * 构造工具调用回调。
     *
     * @param toolName      工具名，不可为空
     * @param arguments     工具参数，可为 {@code null}
     * @param sessionId     会话标识，可为 {@code null}
     * @param timeoutMillis 建议超时（毫秒），不大于 {@code 0} 表示不设截止时间
     * @throws JellyfishException 工具名为空时抛出
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments, String sessionId, long timeoutMillis) {
        super(ToolCallResult.class, sessionId, timeoutMillis);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
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
