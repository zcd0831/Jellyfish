package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：04-perception §4.7。 */
public final class SummarizationCompleted implements DomainEvent {

    @Override
    public String name() {
        return "session.summarization-completed";
    }
}
