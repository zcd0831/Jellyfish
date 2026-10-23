package io.github.zcd0831.jellyfish.domain.action.agent;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

/** 规则出处：模块-行动 §7.4。 */
public class AgentDefinition {

    private AgentId id;
    private String name;
    private AgentSource source;
    private PromptTemplate basePrompt;
    private AgentStatus status;
    private List<ToolId> toolIds;
    private List<SkillId> skillIds;
}
