package io.github.zcd0831.jellyfish.domain.memory.skill;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：模块-记忆 §6。 */
public final class SkillRevised implements DomainEvent {

    @Override
    public String name() {
        return "skill.revised";
    }
}
