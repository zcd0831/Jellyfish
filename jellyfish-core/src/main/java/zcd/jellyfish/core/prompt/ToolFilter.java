package zcd.jellyfish.core.prompt;

import java.util.function.Predicate;

/**
 * 工具清单过滤器：决定「这一轮请求里，模型能看到哪些工具」。
 * <p>
 * <b>它只影响清单，不影响执行</b>：执行期的权限判定仍在 {@code PermissionManager}，
 * 本类做的是「别让模型被邀请去调用一个必然被拒的工具」。判据本身也是从那个判定函数来的
 * （见 {@code PermissionManager#usableTools}），两者不会分叉。
 * <p>
 * <b>只有嵌套回合会传非空过滤</b>：主会话路径永远是 {@link #none()}。子代理与主会话除了传入的任务
 * 之外相互隔离，它只该看到自己那份 agent 配置允许的工具——主会话的工具清单不受任何影响。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolFilter {

    /** 全放行的过滤器。 */
    private static final ToolFilter NONE = new ToolFilter(null);

    /** 判据；{@code null} 表示全放行。 */
    private final Predicate<String> predicate;

    /**
     * 构造过滤器。
     *
     * @param predicate 判据，{@code null} 表示全放行
     */
    private ToolFilter(Predicate<String> predicate) {
        this.predicate = predicate;
    }

    /**
     * 构造全放行的过滤器。
     *
     * @return 过滤器，保证非 {@code null}
     */
    public static ToolFilter none() {
        return NONE;
    }

    /**
     * 按判据构造过滤器。
     *
     * @param predicate 判据，{@code null} 等价于 {@link #none()}
     * @return 过滤器，保证非 {@code null}
     */
    public static ToolFilter of(Predicate<String> predicate) {
        return predicate == null ? NONE : new ToolFilter(predicate);
    }

    /**
     * 判断某个工具是否应当出现在这一轮的清单里。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 应当出现返回 {@code true}
     */
    public boolean accepts(String toolName) {
        return predicate == null || predicate.test(toolName);
    }

    /**
     * 判断是否不做任何过滤。
     * <p>
     * 供调用点跳过无意义的工作（例如探测可用工具数）用；不影响 {@link #accepts(String)} 的语义。
     *
     * @return 全放行返回 {@code true}
     */
    public boolean isNone() {
        return predicate == null;
    }

    @Override
    public String toString() {
        return isNone() ? "ToolFilter{none}" : "ToolFilter{filtered}";
    }
}
