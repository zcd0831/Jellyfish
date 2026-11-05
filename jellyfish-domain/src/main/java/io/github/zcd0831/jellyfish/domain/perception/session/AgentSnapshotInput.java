package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 组装快照的入参：工具/技能已由调用方按 ACTIVE 与绑定裁剪，领域只做组装。
 * 规则出处：04-perception §4.9.3。
 */
@Value
public class AgentSnapshotInput {

    AgentId agentId;
    PromptTemplate basePrompt;
    Map<String, String> promptVariables;
    List<ToolSchemaSnapshot> activeTools;
    List<SkillSummarySnapshot> activeSkills;
    List<String> sopReferencedToolNames;

    private AgentSnapshotInput(Builder builder) {
        this.agentId = builder.agentId;
        this.basePrompt = builder.basePrompt;
        this.promptVariables = builder.promptVariables == null
                ? Collections.<String, String>emptyMap() : builder.promptVariables;
        this.activeTools = immutable(builder.activeTools);
        this.activeSkills = immutable(builder.activeSkills);
        this.sopReferencedToolNames = immutable(builder.sopReferencedToolNames);
    }

    /** 代理身份必填；工具/技能由调用方按 ACTIVE 与绑定裁剪好再传（04-perception §4.9.3）。 */
    public static Builder builder(AgentId agentId) {
        return new Builder(agentId);
    }

    public static final class Builder {

        private final AgentId agentId;
        private PromptTemplate basePrompt;
        private Map<String, String> promptVariables;
        private List<ToolSchemaSnapshot> activeTools;
        private List<SkillSummarySnapshot> activeSkills;
        private List<String> sopReferencedToolNames;

        private Builder(AgentId agentId) {
            if (agentId == null) {
                throw new IllegalArgumentException("组装快照必须指明代理");
            }
            this.agentId = agentId;
        }

        public Builder basePrompt(PromptTemplate value) {
            this.basePrompt = value;
            return this;
        }

        public Builder promptVariables(Map<String, String> value) {
            this.promptVariables = value;
            return this;
        }

        public Builder activeTools(List<ToolSchemaSnapshot> value) {
            this.activeTools = value;
            return this;
        }

        public Builder activeSkills(List<SkillSummarySnapshot> value) {
            this.activeSkills = value;
            return this;
        }

        public Builder sopReferencedToolNames(List<String> value) {
            this.sopReferencedToolNames = value;
            return this;
        }

        public AgentSnapshotInput build() {
            return new AgentSnapshotInput(this);
        }
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(
                source == null ? Collections.<T>emptyList() : source));
    }
}
