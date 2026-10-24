package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：模块-规划 §6。 */
public final class PlanFailed implements DomainEvent {

    @Override
    public String name() {
        return "plan.failed";
    }
}
