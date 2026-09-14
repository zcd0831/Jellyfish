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
 * 不可变：集合在构造时复制并包装为不可修改列表，缺省一律为空列表而非 {@code null}。
 *
 * @author zcd
 */
public class AgentPermissions {

    /** 显式拒绝的工具名。 */
    private final List<String> deniedTools;

    /** 需要人工审批的工具名。 */
    private final List<String> askTools;

    /** 允许的工具名，为空表示不限制。 */
    private final List<String> allowedTools;

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
     *
     * @return 不可修改列表，未配置时为空列表而非 {@code null}；空表示不限制
     */
    public List<String> getAllowedTools() {
        return allowedTools;
    }

    /**
     * 判断是否未声明任何授权。
     *
     * @return 三组都为空返回 {@code true}
     */
    public boolean isEmpty() {
        return deniedTools.isEmpty() && askTools.isEmpty() && allowedTools.isEmpty();
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
