package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.SkillId;
/** 规则出处：模块-记忆 §7.3。 */
public class SkillDefinition {

    private SkillId id;
    private String name;
    private String description;
    private String triggerCondition;
    private InstructionTemplate sopTemplate;
    private SkillStatus status;
}
