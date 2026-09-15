package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * agent 权限段原始值：只承载用户在 {@code agents.json} 里写下的三组工具名。
 * <p>
 * <b>刻意不做判定</b>：三组之间的优先级（显式拒绝 &gt; 需审批 &gt; 允许范围收窄）属于
 * {@code PermissionPolicy} 的语义，空白项与重复项的去重也在那里完成。本类只负责「如实携带」，
 * 因此在配置层与判定层之间留了一道清晰的边界——配置写错不会被这里悄悄修好或悄悄丢掉。
 * <p>
 * <b>「未声明」与「声明为空」是两回事</b>：反序列化时字段缺失得到 {@code null}，显式写
 * {@code []} 得到空集合。前者是「用户没提这件事」，后者是「用户明确要求一个都不允许」——若把两者
 * 一起归一成空列表，{@code "allowedTools": []} 就会被读成「未配置」而全放行。因此允许名单额外记
 * 一个「是否声明」的标记；{@code deniedTools} / {@code askTools} 为空时无论是否声明都不产生任何限制，
 * 不需要区分。
 * <p>
 * 不可变：集合在构造时复制并包装为不可修改列表，缺省一律为空列表而非 {@code null}；「是否声明」
 * 通过 {@link #isAllowListDeclared()} 单独暴露。
 *
 * @author zcd
 */
public class AgentPermissions {

    /** 显式拒绝的工具名。 */
    private final List<String> deniedTools;

    /** 需要人工审批的工具名。 */
    private final List<String> askTools;

    /** 允许的工具名；空列表的含义取决于是否声明（见 {@link #allowListDeclared}）。 */
    private final List<String> allowedTools;

    /** 允许名单是否被显式声明：未声明表示不限制，声明为空表示一个都不允许。 */
    private final boolean allowListDeclared;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param deniedTools  显式拒绝的工具名，可为 {@code null}
     * @param askTools     需要人工审批的工具名，可为 {@code null}
     * @param allowedTools 允许的工具名，可为 {@code null}
     */
    @JsonCreator
    public AgentPermissions(@JsonProperty("deniedTools") List<String> deniedTools,
                            @JsonProperty("askTools") List<String> askTools,
                            @JsonProperty("allowedTools") List<String> allowedTools) {
        this.deniedTools = copyOf(deniedTools);
        this.askTools = copyOf(askTools);
        this.allowedTools = copyOf(allowedTools);
        this.allowListDeclared = allowedTools != null;
    }

    /**
     * 获取显式拒绝的工具名。
     *
     * @return 不可修改列表，未配置时为空列表而非 {@code null}
     */
    public List<String> getDeniedTools() {
        return deniedTools;
    }

    /**
     * 获取需要人工审批的工具名。
     *
     * @return 不可修改列表，未配置时为空列表而非 {@code null}
     */
    public List<String> getAskTools() {
        return askTools;
    }

    /**
     * 获取允许的工具名。
     * <p>
     * 空列表的含义取决于是否声明：未声明表示不限制，声明为空表示一个都不允许（见
     * {@link #isAllowListDeclared()}）。
     *
     * @return 不可修改列表，未配置时为空列表而非 {@code null}
     */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    /**
     * 判断是否未声明任何授权。
     * <p>
     * 「声明为空」也算声明：{@code "allowedTools": []} 表示一个都不允许，因此不算未声明。
     *
     * @return 三组都没写返回 {@code true}
     */
    public boolean isEmpty() {
        return deniedTools.isEmpty() && askTools.isEmpty() && !allowListDeclared;
    }

    /**
     * 判断允许名单是否被显式声明。
     * <p>
     * 未声明（字段缺失）表示不限制；声明为空数组表示一个都不允许——两者必须能区分，否则
     * {@code "allowedTools": []} 会被当成「未配置」而全放行。
     *
     * @return 配置里写了 {@code allowedTools} 字段（哪怕是空数组）返回 {@code true}
     */
    public boolean isAllowListDeclared() {
        return allowListDeclared;
    }

    /**
     * 复制工具名列表并包装为不可修改列表。
     *
     * @param tools 工具名列表，可为 {@code null}
     * @return 不可修改列表，入参为空时返回空列表
     */
    private static List<String> copyOf(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(tools));
    }

    @Override
    public String toString() {
        return "AgentPermissions{deniedTools=" + deniedTools + ", askTools=" + askTools
                + ", allowedTools=" + allowedTools + '}';
    }
}
