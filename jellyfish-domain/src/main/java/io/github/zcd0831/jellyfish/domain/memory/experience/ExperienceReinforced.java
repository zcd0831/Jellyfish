package io.github.zcd0831.jellyfish.domain.memory.experience;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：07-memory §7.7。 */
public final class ExperienceReinforced implements DomainEvent {

    @Override
    public String name() {
        return "experience.reinforced";
    }
}
