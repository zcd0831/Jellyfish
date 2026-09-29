package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * agent 定义：{@code agents.json} 里一个条目的不可变快照。
 * <p>
 * 只承载「这个 agent 长什么样」的静态描述，不含任何运行期状态：会话内的当前 agentId 归
 * {@code SessionManager}，判定与拼接由各自调用点完成。
 * <p>
 * <b>提示词不来自 JSON</b>：{@link #getSystemPrompt()} 的内容由同目录下的 {@code {agentId}.md}
 * 文件提供（见 {@code AgentPromptLoader}），JSON 里不再接受 {@code systemPrompt} 字段——提示词往往是
 * 多段长文，塞进一行 JSON 字符串既难维护也难阅读。反序列化得到的对象该字段必为 {@code null}，
 * 由加载阶段用 {@link #withSystemPrompt(String)} 补上。
 * <p>
 * <b>提示词是原文</b>：不做模板插值、不与工具清单拼接——插值会让提示词里的 {@code ${...}}
 * 与配置层的环境变量占位符语义打架（配置层只提供 {@code \${}} 转义），拼接则属于 {@code core/prompt}
 * 的职责。
 * <p>
 * <b>{@code delegatable} 只回答「能不能被 {@code task} 工具委派为目标」</b>，不回答「它自己能不能
 * 再往下委派」。后者由内核的委派层数上限与「该 agent 的工具清单里有没有 {@code task}」共同决定，
 * 因此不需要第二个字段——一个字段两种含义会让「这个 agent 到底能不能用」变成要读两处才能回答的问题。
 * 缺省 {@code false}：委派目标是显式配置出来的，不是「配了 agent 就自动能当子代理」。
 * <p>
 * <b>{@code model} 是该 agent 的偏好模型</b>，写法与 {@code /model} 命令一致：
 * {@code provider/model} 或裸 {@code model} 名。它的优先级低于「会话显式选定的模型」、高于全局默认，
 * 因此绑定 agent 会让未显式选过模型的会话落到该 agent 的模型上；{@code null} 表示继续跟随全局默认。
 * <p>
 * {@code agentId} 与 {@link Provider#getName()} 同款：配置文件里的映射 key 才是唯一标识，
 * 由合并阶段用 {@link #withAgentId(String)} 回填，因此条目内不需要（也不允许）重复写一遍。
 * 内置默认 agent 是唯一例外——它的 {@code default-agent.json} 里显式声明了 {@code agentId}。
 *
 * @author zcd
 */
public class AgentDefinition {

    /** agent 标识，取自配置文件中 agents 的 key。 */
    private final String agentId;

    /** 用途描述，供 UI 与诊断展示。 */
    private final String description;

    /**
     * 系统提示词原文，来自同目录的 {@code {agentId}.md}。
     * <p>
     * 标 {@link JsonIgnore} 是必须的：Jackson 默认允许对 final 字段做反射赋值（
     * {@code ALLOW_FINAL_FIELDS_AS_MUTATORS} 默认为 true），光把它从构造器里去掉并不能阻止
     * JSON 里的 {@code systemPrompt} 被写进来。
     */
    @JsonIgnore
    private final String systemPrompt;

    /** 权限段，缺省为空对象而非 {@code null}。 */
    private final AgentPermissions permissions;

    /** 是否可作为 {@code task} 工具的委派目标，缺省 {@code false}。 */
    private final boolean delegatable;

    /** 该 agent 的偏好模型引用（{@code provider/model} 或裸 model 名），{@code null} 表示跟随全局默认。 */
    private final String model;

    /**
     * 反序列化构造器：只接受 JSON 里真正允许声明的字段。
     * <p>
     * 刻意不声明 {@code systemPrompt}：提示词已改由 Markdown 文件提供，若仍在这里接收，
     * JSON 与 md 谁生效就成了隐含约定。字段不在构造器里，Jackson 的未知字段策略会静默忽略它。
     *
     * @param agentId     agent 标识，可为 {@code null}（由合并阶段按配置 key 回填）
     * @param description 用途描述，可为 {@code null}
     * @param permissions 权限段，可为 {@code null}（按空处理）
     * @param delegatable 是否可被委派，可为 {@code null}（按 {@code false} 处理）
     * @param model       偏好模型引用，可为 {@code null}（跟随全局默认）
     */
    @JsonCreator
    public AgentDefinition(@JsonProperty("agentId") String agentId,
                           @JsonProperty("description") String description,
                           @JsonProperty("permissions") AgentPermissions permissions,
                           @JsonProperty("delegatable") Boolean delegatable,
                           @JsonProperty("model") String model) {
        this(agentId, description, null, permissions, Boolean.TRUE.equals(delegatable), model);
    }

    /**
     * 便捷构造器：不带委派声明与偏好模型。
     * <p>
     * 保留它是为了源码兼容：绝大多数构造点（测试与内部工具）只关心前三个字段。
     *
     * @param agentId     agent 标识，可为 {@code null}
     * @param description 用途描述，可为 {@code null}
     * @param permissions 权限段，可为 {@code null}（按空处理）
     */
    public AgentDefinition(String agentId, String description, AgentPermissions permissions) {
        this(agentId, description, null, permissions, false, null);
    }

    /**
     * 全参数构造器，供各 {@code withXxx} 副本方法复用。
     *
     * @param agentId      agent 标识
     * @param description  用途描述
     * @param systemPrompt 系统提示词原文
     * @param permissions  权限段，可为 {@code null}（按空处理）
     * @param delegatable  是否可被委派
     * @param model        偏好模型引用，可为 {@code null}
     */
    private AgentDefinition(String agentId, String description, String systemPrompt,
                            AgentPermissions permissions, boolean delegatable, String model) {
        this.agentId = agentId;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.permissions = permissions == null ? new AgentPermissions(null, null, null) : permissions;
        this.delegatable = delegatable;
        this.model = model;
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
        return new AgentDefinition(agentId, description, systemPrompt, permissions, delegatable, model);
    }

    /**
     * 补上从 Markdown 文件读到的提示词，返回副本。
     *
     * @param systemPrompt 提示词原文，可为 {@code null}（该 agent 没有同名 md 文件）
     * @return 补上提示词后的副本
     */
    public AgentDefinition withSystemPrompt(String systemPrompt) {
        return new AgentDefinition(agentId, description, systemPrompt, permissions, delegatable, model);
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
     * @return 提示词原文；未提供同名 md 文件时为 {@code null}
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

    /**
     * 判断该 agent 是否可作为 {@code task} 工具的委派目标。
     *
     * @return 可被委派返回 {@code true}
     */
    public boolean isDelegatable() {
        return delegatable;
    }

    /**
     * 获取该 agent 的偏好模型引用。
     *
     * @return 模型引用（{@code provider/model} 或裸 model 名）；未配置时为 {@code null}
     */
    public String getModel() {
        return model;
    }

    @Override
    public String toString() {
        // 提示词可能很长且含敏感内容，诊断输出只带标识
        return "AgentDefinition{agentId=" + agentId + '}';
    }
}
