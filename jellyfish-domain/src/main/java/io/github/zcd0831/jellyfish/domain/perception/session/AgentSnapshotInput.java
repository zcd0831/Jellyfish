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

    public AgentSnapshotInput(AgentId agentId, PromptTemplate basePrompt, Map<String, String> promptVariables,
                              List<ToolSchemaSnapshot> activeTools, List<SkillSummarySnapshot> activeSkills,
                              List<String> sopReferencedToolNames) {
        this.agentId = agentId;
        this.basePrompt = basePrompt;
        this.promptVariables = promptVariables == null
                ? Collections.<String, String>emptyMap() : promptVariables;
        this.activeTools = immutable(activeTools);
        this.activeSkills = immutable(activeSkills);
        this.sopReferencedToolNames = immutable(sopReferencedToolNames);
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(
                source == null ? Collections.<T>emptyList() : source));
    }
}
