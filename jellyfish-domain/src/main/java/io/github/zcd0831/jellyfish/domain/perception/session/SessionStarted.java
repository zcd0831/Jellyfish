package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：04-perception §4.7、§4.8.1 规则 1。 */
public final class SessionStarted implements DomainEvent {

    @Override
    public String name() {
        return "session.started";
    }
}
