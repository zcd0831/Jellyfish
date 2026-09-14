package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code agents.json} 反序列化后的原始结构。
 * <p>
 * 只承载「单份文件」的内容，不做 global/project 合并；合并结果由 {@link RuntimeConfig} 产出，
 * 每次都构造新实例，因此同一份文件被重复解析时互不影响。
 * <p>
 * {@code agentId} 由 {@code agents} 的 key 决定（由合并阶段回填到 {@link AgentDefinition}），
 * 条目内部不重复声明标识，与 {@code providers} 的处理方式一致。
 * <p>
 * 不可变：不存在 setter，{@code agents} 以不可变映射发布。
 *
 * @author zcd
 */
public class AgentSettings {

    /** 默认 agentId。 */
    private final String defaultAgent;

    /** agentId → agent 定义，不可变；未配置时为空映射而非 {@code null}。 */
    private final Map<String, AgentDefinition> agents;

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param defaultAgent 默认 agentId，可为 {@code null}
     * @param agents       agentId 到 agent 定义的映射，可为 {@code null}
     */
    @JsonCreator
    public AgentSettings(@JsonProperty("defaultAgent") String defaultAgent,
                         @JsonProperty("agents") Map<String, AgentDefinition> agents) {
        this.defaultAgent = defaultAgent;
        this.agents = agents == null
                ? Collections.<String, AgentDefinition>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(agents));
    }

    /**
     * 获取默认 agentId。
     *
     * @return 默认 agentId，未配置时为 {@code null}
     */
    public String getDefaultAgent() {
        return defaultAgent;
    }

    /**
     * 获取 agent 定义。
     *
     * @return agentId 到 agent 定义的映射，可能为空但不会为 {@code null}，顺序与配置一致
     */
    public Map<String, AgentDefinition> getAgents() {
        return agents;
    }

    /**
     * 判断是否未配置任何 agent。
     *
     * @return 没有任何 agent 条目返回 {@code true}
     */
    public boolean isEmpty() {
        return agents.isEmpty();
    }
}
