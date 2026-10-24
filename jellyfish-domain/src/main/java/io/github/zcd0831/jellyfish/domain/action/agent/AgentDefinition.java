package io.github.zcd0831.jellyfish.domain.action.agent;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 代理身份与能力边界。规则出处：模块-行动 §3.3、§7.4。 */
public class AgentDefinition {

    private final AgentId id;
    private final AgentSource source;
    private final List<ToolId> toolIds = new ArrayList<ToolId>();
    private final List<SkillId> skillIds = new ArrayList<SkillId>();

    private String name;
    private PromptTemplate basePrompt;
    private AgentStatus status;

    public AgentDefinition(AgentId id, String name, AgentSource source, PromptTemplate basePrompt,
                           AgentStatus status, List<ToolId> toolIds, List<SkillId> skillIds) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.basePrompt = basePrompt;
        this.status = status;
        if (toolIds != null) {
            this.toolIds.addAll(toolIds);
        }
        if (skillIds != null) {
            this.skillIds.addAll(skillIds);
        }
    }

    /** 规则出处：模块-行动 §7.4 规则 ①（内置代理 prompt 不可编辑）。 */
    public void updatePrompt(PromptTemplate prompt) {
        if (source == AgentSource.BUILT_IN) {
            throw new io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation(
                    "内置代理的提示词不可编辑：" + id.getValue());
        }
        this.basePrompt = prompt;
    }

    public void bindTool(ToolId toolId) {
        if (!toolIds.contains(toolId)) {
            toolIds.add(toolId);
        }
    }

    public void unbindTool(ToolId toolId) {
        toolIds.remove(toolId);
    }

    public void bindSkill(SkillId skillId) {
        if (!skillIds.contains(skillId)) {
            skillIds.add(skillId);
        }
    }

    public void activate() {
        this.status = AgentStatus.ACTIVE;
    }

    public void deprecate() {
        this.status = AgentStatus.DEPRECATED;
    }

    public boolean isActive() {
        return status == AgentStatus.ACTIVE;
    }

    /** 绑定工具只作为"本代理能用哪些"的依据，会话快照由感知按此裁剪（§7.4 规则 ③）。 */
    public List<ToolId> listBoundToolIds() {
        return Collections.unmodifiableList(toolIds);
    }

    public List<SkillId> listBoundSkillIds() {
        return Collections.unmodifiableList(skillIds);
    }

    public AgentId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public AgentSource source() {
        return source;
    }

    public PromptTemplate basePrompt() {
        return basePrompt;
    }

    public AgentStatus status() {
        return status;
    }

    public void rename(String name) {
        this.name = name;
    }
}
