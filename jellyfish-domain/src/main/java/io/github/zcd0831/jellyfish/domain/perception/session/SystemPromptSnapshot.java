package io.github.zcd0831.jellyfish.domain.perception.session;

import java.util.List;

import lombok.Value;

/** 会话开启时冻结的内容快照。规则出处：04-perception §4.8.1 规则 4。 */
@Value
public class SystemPromptSnapshot {

    String renderedPrompt;
    List<ToolSchemaSnapshot> toolList;
    List<SkillSummarySnapshot> skillList;
}
