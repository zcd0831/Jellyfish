package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 输入指令结果：插件对「这行输入是不是我管的」的回答，以及「那我该做什么」的声明。
 * <p>
 * <b>它只是声明，不是执行</b>：插件能表达的最强意图是「请内核用这个工具、这几个参数去跑一次」，
 * 执行权始终在内核手里（{@code ToolExecutor} → 权限判定 → 审批 → 截断）。
 * 这条边界是刻意的——插件拿不到 {@code PermissionManager}，因此插件<b>不能</b>自己起进程或写文件，
 * 也就无法绕过核心权限策略。<b>本类型不提供任何「直接执行」的入口</b>。
 * <p>
 * 两种形态：
 * <ul>
 *     <li>{@link #toolCall(String, Map)}：内核按工具名路由执行，结果进上下文；</li>
 *     <li>{@link #unclaimed()}：这行输入不是本处理器要管的（例如标记相同但语法不匹配），
 *     调用方应当把它当普通文本继续处理。</li>
 * </ul>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputDirectiveResult {

    /** 结果形态。 */
    private enum Kind {

        /** 请内核执行一次工具调用。 */
        TOOL_CALL,

        /** 本处理器不认领这行输入。 */
        UNCLAIMED
    }

    /** 不认领的单例：它没有负载，重复创建没有意义。 */
    private static final InputDirectiveResult UNCLAIMED = new InputDirectiveResult(Kind.UNCLAIMED, null, null);

    /** 结果形态。 */
    private final Kind kind;

    /** 工具名，仅 {@link Kind#TOOL_CALL} 时有值。 */
    private final String toolName;

    /** 工具参数，仅 {@link Kind#TOOL_CALL} 时有值，保证非 {@code null}。 */
    private final Map<String, Object> arguments;

    /**
     * 构造结果。
     *
     * @param kind      结果形态
     * @param toolName  工具名，可为 {@code null}
     * @param arguments 工具参数，可为 {@code null}
     */
    private InputDirectiveResult(Kind kind, String toolName, Map<String, Object> arguments) {
        this.kind = kind;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    /**
     * 声明一次工具调用。
     *
     * @param toolName  工具名（路由键），不可为空白
     * @param arguments 工具参数，可为 {@code null}（等价空映射）
     * @return 工具调用意图，保证非 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public static InputDirectiveResult toolCall(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new JellyfishException("input directive tool name must not be blank");
        }
        return new InputDirectiveResult(Kind.TOOL_CALL, toolName, arguments);
    }

    /**
     * 声明这行输入不由本处理器认领。
     *
     * @return 不认领结果，保证非 {@code null}
     */
    public static InputDirectiveResult unclaimed() {
        return UNCLAIMED;
    }

    /**
     * 判断是否为工具调用意图。
     *
     * @return 是工具调用返回 {@code true}
     */
    public boolean isToolCall() {
        return kind == Kind.TOOL_CALL;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名；不是工具调用意图时为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 不可变参数映射；不是工具调用意图时为空映射
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "InputDirectiveResult{kind=" + kind + ", toolName=" + toolName + '}';
    }
}
