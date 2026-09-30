package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果整形请求：内核在<b>截断与落盘之前</b>询问「这条结果要不要整形」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}），理由与
 * {@link ToolArgumentPreRequest} 相同。需要按工具名分流时，处理器自己看 {@link #getToolName()}。
 * <p>
 * <b>为什么必须排在截断之前</b>：截断之后回来改文本，会产出「信封说内容被截断、正文却是完整的」
 * 这类自相矛盾的结果；而且落盘文件是在截断那一步写的，后置变换改不动它，
 * 于是「回灌给模型的文本」与「{@code _path} 里的内容」永久分叉。
 * <p>
 * <b>输出保持原始类型</b>：字符串就是字符串、结构化对象就是结构化对象，
 * 此处<b>不得</b>把它序列化成文本——截断要知道「这是字符串还是结构化对象」才能选对算法。
 * <p>
 * <b>能改的与不能改的</b>：
 * <ul>
 *     <li>{@link #getOutput()}：可改。脱敏、归一化、加审计头都在这里；</li>
 *     <li>{@link #getMetadata()}：可改。约定的三个键本来就归工具自己填，内核只透传不解释；</li>
 *     <li>{@link #isFailed()}：只读。<b>不能改</b>，它由「工具有没有抛」与元数据的约定键决定，
 *     是界面判成败的唯一判据——放开它等于引入第二套判据；</li>
 *     <li>已落盘的文件内容：够不到，它在截断那一步才产生。</li>
 * </ul>
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolResultPostRequest extends ExtensionRequest<ToolResultAdjustment> {

    /** 发起调用的 agentId，未绑定 agent 时为 {@code null}。 */
    private final String agentId;

    /** 本次调用的工具名。 */
    private final String toolName;

    /** 本次调用实际使用的参数（即参数改写之后的最终值）。 */
    private final Map<String, Object> arguments;

    /** 工具产出的原始结果，类型保持原样。 */
    private final Object output;

    /** 结构化元数据，只读。 */
    private final Map<String, Object> metadata;

    /** 本次调用是否值得警示。 */
    private final boolean failed;

    /**
     * 构造结果整形请求。
     * <p>
     * {@code failed} 是调用方按 {@link ToolMetadata#failed(Map)} 算好的值（不是在这里算），
     * 目的是让插件看到的判据与界面将要用的判据<b>是同一个</b>。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  本次调用的工具名，不可为空白
     * @param arguments 实际使用的参数，可为 {@code null}
     * @param output    工具产出的原始结果，可为 {@code null}
     * @param metadata  结构化元数据，可为 {@code null}
     * @param failed    本次调用是否值得警示
     * @param sessionId 会话标识，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolResultPostRequest(String agentId, String toolName, Map<String, Object> arguments, Object output,
                                 Map<String, Object> metadata, boolean failed, String sessionId) {
        super(ToolResultAdjustment.class, sessionId);
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("tool name must not be blank");
        }
        this.agentId = agentId;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
        this.output = output;
        this.metadata = metadata == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.failed = failed;
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
     * 获取本次调用的工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取本次调用实际使用的参数。
     * <p>
     * 它是<b>参数改写之后</b>的最终值，与权限判定、审批展示、会话落库看到的是同一份。
     *
     * @return 只读参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取工具产出的原始结果。
     *
     * @return 原始结果，可能为 {@code null}
     */
    public Object getOutput() {
        return output;
    }

    /**
     * 获取结构化元数据。
     *
     * @return 只读元数据映射，保证非 {@code null}
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 判断本次调用是否值得警示。
     * <p>
     * 只读：它由 {@link ToolMetadata#failed(Map)} 给出，界面渲染警示标记时用的是同一个判据。
     *
     * @return 值得警示返回 {@code true}
     */
    public boolean isFailed() {
        return failed;
    }
}
