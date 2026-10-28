package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：06-planning §6.7。 */
public final class PlanCompleted implements DomainEvent {

    @Override
    public String name() {
        return "plan.completed";
    }
}
