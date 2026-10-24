package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.SkillId;

/** 程序性知识（SOP）。规则出处：模块-记忆 §3.3、§7.3。 */
public class SkillDefinition {

    private final SkillId id;
    private final InstructionTemplate sopTemplate;

    private String name;
    private String description;
    private String triggerCondition;
    private SkillStatus status;

    public SkillDefinition(SkillId id, String name, String description, String triggerCondition,
                           InstructionTemplate sopTemplate, SkillStatus status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.triggerCondition = triggerCondition;
        this.sopTemplate = sopTemplate;
        this.status = status;
    }

    /** 规则出处：模块-记忆 §7.3 规则 ①（只有 ACTIVE 进入快照）。 */
    public boolean isActive() {
        return status == SkillStatus.ACTIVE;
    }

    public void activate() {
        this.status = SkillStatus.ACTIVE;
    }

    public void deprecate() {
        this.status = SkillStatus.DEPRECATED;
    }

    public SkillId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String triggerCondition() {
        return triggerCondition;
    }

    public InstructionTemplate sopTemplate() {
        return sopTemplate;
    }

    public SkillStatus status() {
        return status;
    }
}
