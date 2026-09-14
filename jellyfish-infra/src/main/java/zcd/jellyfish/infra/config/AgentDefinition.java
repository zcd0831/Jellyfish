package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * agent 定义：{@code agents.json} 里一个条目的不可变快照。
 * <p>
 * 只承载「这个 agent 长什么样」的静态描述，不含任何运行期状态：会话内的当前 agentId 归
 * {@code SessionManager}，判定与拼接由各自调用点完成。
 * <p>
 * <b>{@link #getSystemPrompt()} 是原文</b>：不做模板插值、不引用外部文件、不与工具清单拼接——
 * 插值会让提示词里的 {@code ${...}} 与配置层的环境变量占位符语义打架（配置层只提供
 * {@code \${}} 转义），拼接则属于 {@code core/prompt} 的职责。
 * <p>
 * {@code agentId} 与 {@link Provider#getName()} 同款：配置文件里的映射 key 才是唯一标识，
 * 由合并阶段用 {@link #withAgentId(String)} 回填，因此条目内不需要（也不允许）重复写一遍。
 *
 * @author zcd
 */
public class AgentDefinition {

    /** agent 标识，取自配置文件中 agents 的 key。 */
    private final String agentId;

    /** 用途描述，供 UI 与诊断展示。 */
    private final String description;

    /** 系统提示词原文。 */
    private final String systemPrompt;

    /** 权限段，缺省为空对象而非 {@code null}。 */
    private final AgentPermissions permissions;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param agentId      agent 标识，可为 {@code null}（由合并阶段按配置 key 回填）
     * @param description  用途描述，可为 {@code null}
     * @param systemPrompt 系统提示词原文，可为 {@code null}
     * @param permissions  权限段，可为 {@code null}（按空处理）
     */
    @JsonCreator
    public AgentDefinition(@JsonProperty("agentId") String agentId,
                           @JsonProperty("description") String description,
                           @JsonProperty("systemPrompt") String systemPrompt,
                           @JsonProperty("permissions") AgentPermissions permissions) {
        this.agentId = agentId;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.permissions = permissions == null ? new AgentPermissions(null, null, null) : permissions;
    }

    /**
     * 以配置 key 回填标识，返回副本。
     * <p>
     * 不回写原对象：同一个 {@link AgentDefinition} 可能被全局级与项目级两份配置共享，
     * 就地改名会让「哪份配置的 key 生效」变得不可追溯。
     *
     * @param agentId 配置里该条目的 key
     * @return 回填后的副本
     */
    public AgentDefinition withAgentId(String agentId) {
        return new AgentDefinition(agentId, description, systemPrompt, permissions);
    }

    /**
     * 获取 agent 标识。
     *
     * @return agent 标识，未回填时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取用途描述。
     *
     * @return 用途描述，未配置时为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取系统提示词原文。
     *
     * @return 提示词原文，未配置时为 {@code null}
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 获取权限段。
     *
     * @return 权限段，保证非 {@code null}
     */
    public AgentPermissions getPermissions() {
        return permissions;
    }

    @Override
    public String toString() {
        // 提示词可能很长且含敏感内容，诊断输出只带标识
        return "AgentDefinition{agentId=" + agentId + '}';
    }
}
