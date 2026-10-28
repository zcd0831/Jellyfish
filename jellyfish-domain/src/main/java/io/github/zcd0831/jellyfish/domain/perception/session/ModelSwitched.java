package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：04-perception §4.7、05-action §5.8.3 规则 3。 */
public final class ModelSwitched implements DomainEvent {

    @Override
    public String name() {
        return "session.model-switched";
    }
}
