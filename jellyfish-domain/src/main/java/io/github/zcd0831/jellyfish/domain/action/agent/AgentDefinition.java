package io.github.zcd0831.jellyfish.domain.action.agent;

import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 代理身份与能力边界。规则出处：05-action §5.4.3、§5.8.4。 */
public class AgentDefinition {

    private final AgentId id;
    private final AgentSource source;
    private final List<ToolId> toolIds = new ArrayList<ToolId>();
    private final List<SkillId> skillIds = new ArrayList<SkillId>();

    private String name;
    private PromptTemplate basePrompt;
    private AgentStatus status;

    private AgentDefinition(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.source = builder.source;
        this.basePrompt = builder.basePrompt;
        this.status = builder.status;
        if (builder.toolIds != null) {
            this.toolIds.addAll(builder.toolIds);
        }
        if (builder.skillIds != null) {
            this.skillIds.addAll(builder.skillIds);
        }
    }

    /** 身份与来源是必填项；绑定与状态按需链式设置。 */
    public static Builder builder(AgentId id, String name, AgentSource source) {
        return new Builder(id, name, source);
    }

    public static final class Builder {

        private final AgentId id;
        private final String name;
        private final AgentSource source;
        private PromptTemplate basePrompt;
        private AgentStatus status = AgentStatus.DRAFT;
        private List<ToolId> toolIds;
        private List<SkillId> skillIds;

        private Builder(AgentId id, String name, AgentSource source) {
            if (id == null || source == null) {
                throw new IllegalArgumentException("代理 id 与来源不能为空");
            }
            this.id = id;
            this.name = name;
            this.source = source;
        }

        public Builder basePrompt(PromptTemplate value) {
            this.basePrompt = value;
            return this;
        }

        public Builder status(AgentStatus value) {
            this.status = value;
            return this;
        }

        public Builder toolIds(List<ToolId> value) {
            this.toolIds = value;
            return this;
        }

        public Builder skillIds(List<SkillId> value) {
            this.skillIds = value;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(this);
        }
    }

    /** 规则出处：05-action §5.8.4 规则 1（内置代理 prompt 不可编辑）。 */
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
