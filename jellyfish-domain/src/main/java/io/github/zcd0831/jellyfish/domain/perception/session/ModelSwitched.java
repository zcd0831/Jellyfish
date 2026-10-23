package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：模块-感知 §6、模块-行动 §7.3 规则 ③。 */
public final class ModelSwitched implements DomainEvent {

    @Override
    public String name() {
        return "session.model-switched";
    }
}
