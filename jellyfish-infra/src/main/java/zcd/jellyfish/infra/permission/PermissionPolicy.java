package zcd.jellyfish.infra.permission;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * agent 粒度权限策略：内核判定「这个 agent 能不能用某个工具」的输入。
 * <p>
 * 只表达「授权态度」，不感知会话模式、插件拦截与审计：PLAN 模式与只读白名单由
 * {@code PermissionManager} 在调用点组合，插件拦截走扩展层，三者互不侵入。
 * <p>
 * 三个集合都为空表示「无策略」，内核按 fail-open 放行。判定优先级固定为
 * 「显式拒绝 &gt; 需审批 &gt; 允许范围收窄」，因此同一个工具既出现在 {@code deniedTools}
 * 又出现在 {@code allowedTools} 时结论是拒绝——收窄永远优先于放宽。
 * <p>
 * 不可变，可安全跨线程传递；构造时忽略空白项与重复项。
 *
 * @author zcd
 */
public final class PermissionPolicy {

    /** 无策略实例：三个集合都为空，判定结果恒为「不限制」。 */
    private static final PermissionPolicy UNRESTRICTED = new PermissionPolicy(null, null, null);

    /** 显式拒绝的工具名。 */
    private final Set<String> deniedTools;

    /** 需要人工审批的工具名。 */
    private final Set<String> askTools;

    /** 允许的工具名，为空表示不限制。 */
    private final Set<String> allowedTools;

    /**
     * 构造策略。
     *
     * @param deniedTools  显式拒绝的工具名，可为 {@code null}
     * @param askTools     需要人工审批的工具名，可为 {@code null}
     * @param allowedTools 允许的工具名，可为 {@code null}
     */
    private PermissionPolicy(Set<String> deniedTools, Set<String> askTools, Set<String> allowedTools) {
        this.deniedTools = copyOf(deniedTools);
        this.askTools = copyOf(askTools);
        this.allowedTools = copyOf(allowedTools);
    }

    /**
     * 构造无策略：不限制任何工具，是 fail-open 的载体。
     *
     * @return 无策略
     */
    public static PermissionPolicy unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * 构造策略。
     *
     * @param deniedTools  显式拒绝的工具名，可为 {@code null}
     * @param askTools     需要人工审批的工具名，可为 {@code null}
     * @param allowedTools 允许的工具名，为空表示不限制，可为 {@code null}
     * @return 策略
     */
    public static PermissionPolicy of(Set<String> deniedTools, Set<String> askTools, Set<String> allowedTools) {
        return new PermissionPolicy(deniedTools, askTools, allowedTools);
    }

    /**
     * 判断是否无策略（三个集合都为空）。
     *
     * @return 无策略返回 {@code true}
     */
    public boolean isEmpty() {
        return deniedTools.isEmpty() && askTools.isEmpty() && allowedTools.isEmpty();
    }

    /**
     * 判断工具是否被显式拒绝。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 显式拒绝返回 {@code true}
     */
    public boolean denies(String toolName) {
        return toolName != null && deniedTools.contains(toolName);
    }

    /**
     * 判断工具是否需要人工审批。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 需要审批返回 {@code true}
     */
    public boolean requiresApproval(String toolName) {
        return toolName != null && askTools.contains(toolName);
    }

    /**
     * 判断工具是否在允许范围内。
     * <p>
     * 允许集合为空表示「不限制」，此时一律返回 {@code true}；非空时只放行命中项。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 在允许范围内返回 {@code true}
     */
    public boolean allows(String toolName) {
        return allowedTools.isEmpty() || (toolName != null && allowedTools.contains(toolName));
    }

    /**
     * 复制工具名集合：忽略空白项与重复项，并包装为不可修改集合。
     *
     * @param tools 工具名集合，可为 {@code null}
     * @return 不可修改集合，入参为空时返回空集合
     */
    private static Set<String> copyOf(Set<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> copied = new LinkedHashSet<>(tools.size());
        for (String tool : tools) {
            if (tool != null && !tool.trim().isEmpty()) {
                copied.add(tool);
            }
        }
        return Collections.unmodifiableSet(copied);
    }
}
