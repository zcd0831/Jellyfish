package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数改写请求：内核在<b>权限判定之前</b>询问「这条参数要不要改写」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：它针对的是「一次工具调用」
 * 这个整体，不按工具名分槽。按工具名分槽会让两个插件都想处理同一个工具时撞「同键唯一」，
 * 与「横切对所有插件开放」的目标冲突。需要按工具名分流时，处理器自己看 {@link #getToolName()}。
 * <p>
 * <b>为什么必须排在权限判定之前</b>：若排在之后，就会出现「审批浮层上显示参数 A、真正执行参数 B」
 * 的 TOCTOU——用户批准的东西与执行的东西不是同一个。排在之前，则<b>批准的就是执行的</b>：
 * 审批记录、界面轨迹行、{@code -cli --show-tool-args}、会话里落库的 {@code toolCalls}
 * 全部是变换后的同一份文本。
 * <p>
 * <b>这不是一条绕过权限的旁路</b>：改写之后照旧要过 {@code PermissionCheckRequest}，
 * 而插件本来就是同进程的任意代码。安全边界是「装不装这个插件」，不是「参数在第几步被看」。
 * <p>
 * <b>不许在这里做校验</b>：内核没有工具的参数 schema，因此改写后的参数<b>不重新校验</b>；
 * 写 {@link ToolArgumentDecision#replace} 的插件自己保证形状合法。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolArgumentPreRequest extends ExtensionRequest<ToolArgumentDecision> {

    /** 工具调用的发起方。 */
    public enum Source {
        /** 模型发起的工具调用（ReAct 循环）。 */
        MODEL,
        /**
         * 用户发起的工具调用（输入指令，如 {@code !ls}）。
         * <p>
         * 带上这一档是为了让「对模型更严、对用户直接放行」这类策略写得出来：
         * 这两条路径的可信度本来就不一样。
         */
        DIRECTIVE
    }

    /** 发起调用的 agentId，未绑定 agent 时为 {@code null}。 */
    private final String agentId;

    /** 待执行的工具名。 */
    private final String toolName;

    /** 当前参数，只读。 */
    private final Map<String, Object> arguments;

    /** 发起方。 */
    private final Source source;

    /**
     * 构造参数改写请求。
     * <p>
     * {@code agentId} 允许为空：未绑定 agent 时视为「无策略」，与 {@code PermissionCheckRequest}
     * 同口径。参数做了防御性拷贝，插件改不动内核手里那份。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  待执行的工具名，不可为空白
     * @param arguments 当前参数，可为 {@code null}
     * @param source    发起方，可为 {@code null}（按 {@link Source#MODEL} 处理）
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolArgumentPreRequest(String agentId, String toolName, Map<String, Object> arguments,
                                  Source source, String sessionId) {
        super(ToolArgumentDecision.class, sessionId);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.agentId = agentId;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
        this.source = source == null ? Source.MODEL : source;
    }

    @Override
    public String getRouteKey() {
        return null;
    }

    /**
     * 获取发起调用的 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取待执行的工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取当前参数。
     * <p>
     * 链式传递时，它是<b>上一个处理器产出的</b>值（首个处理器拿到的是原始参数）。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取发起方。
     *
     * @return 发起方，保证非 {@code null}
     */
    public Source getSource() {
        return source;
    }
}
